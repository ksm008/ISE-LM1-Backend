package iselm1.ebpf;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class EbpfLogService {

    private static final String ORACLE_AGENT_URL = "http://100.100.215.116:9000/stream?containerId=";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String labId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(labId, emitter);

        emitter.onCompletion(() -> emitters.remove(labId));
        emitter.onTimeout(() -> emitters.remove(labId));
        emitter.onError(e -> emitters.remove(labId));

        return emitter;
    }

    public void startStreaming(String labId, String containerId) {
        executor.submit(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ORACLE_AGENT_URL + containerId))
                    .GET()
                    .build();

            try {
                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    sendLog(labId, "[ERROR] eBPF 에이전트 연결 실패: HTTP " + response.statusCode());
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
            } catch (Exception ignored) {
                // 세션 종료로 인한 스트림 닫힘
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
            } catch (IOException e) {
                emitters.remove(labId);
            }
        }
    }
}