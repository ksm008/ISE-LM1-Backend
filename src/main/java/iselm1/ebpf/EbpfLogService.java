package iselm1.ebpf;

import iselm1.config.LabProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class EbpfLogService implements DisposableBean {

    private final String streamUrl;
    private final long sseTimeoutMillis;
    private final int maxSubscriptions;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();

    public EbpfLogService(LabProperties labProperties) {
        String agentUrl = labProperties.ebpf().agentUrl().replaceAll("/+$", "");
        this.streamUrl = agentUrl + "/stream?containerId=";
        this.sseTimeoutMillis = labProperties.session().sseTimeout().toMillis();
        this.maxSubscriptions = labProperties.session().maxSessions();
    }

    /**
     * labId 에 대한 SSE 구독을 만든다. 구독 수가 상한에 도달하면 null 을 반환한다.
     * 같은 labId 로 다시 구독하면(EventSource 자동 재연결) 기존 구독을 교체한다.
     */
    public SseEmitter subscribe(String labId) {
        if (!emitters.containsKey(labId) && emitters.size() >= maxSubscriptions) {
            return null;
        }

        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        emitter.onCompletion(() -> emitters.remove(labId, emitter));
        emitter.onTimeout(() -> emitters.remove(labId, emitter));
        emitter.onError(e -> emitters.remove(labId, emitter));

        SseEmitter previous = emitters.put(labId, emitter);
        if (previous != null) {
            previous.complete();
        }
        return emitter;
    }

    public void startStreaming(String labId, String containerId) {
        executor.submit(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl + URLEncoder.encode(containerId, StandardCharsets.UTF_8)))
                    .GET()
                    .build();

            try {
                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    log.warn("eBPF 에이전트 연결 실패: HTTP {}", response.statusCode());
                    response.body().close();
                    sendLog(labId, "[ERROR] eBPF 모니터링 연결에 실패했습니다.");
                    return;
                }

                InputStream is = response.body();
                activeStreams.put(labId, is);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sendLog(labId, line);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // stopStreaming 에서 스트림을 닫은 경우가 아니라면 기록한다.
                if (activeStreams.containsKey(labId)) {
                    log.warn("eBPF 로그 스트림 오류 (labId={})", labId, e);
                }
            } finally {
                activeStreams.remove(labId);
            }
        });
    }

    public void stopStreaming(String labId) {
        InputStream is = activeStreams.remove(labId);
        if (is != null) {
            try {
                is.close();
            } catch (IOException ignored) {}
        }

        SseEmitter emitter = emitters.remove(labId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    private void sendLog(String labId, String message) {
        SseEmitter emitter = emitters.get(labId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().data(message));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(labId, emitter);
            }
        }
    }

    @Override
    public void destroy() {
        activeStreams.keySet().forEach(this::stopStreaming);
        emitters.keySet().forEach(this::stopStreaming);
        executor.shutdownNow();
    }
}
