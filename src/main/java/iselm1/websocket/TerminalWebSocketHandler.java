package iselm1.websocket;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import iselm1.config.LabIds;
import iselm1.config.LabProperties;
import iselm1.ebpf.EbpfLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    /** 이 서버가 만든 실습 컨테이너 표시. 남은 컨테이너 정리에 사용한다. */
    private static final String LAB_LABEL = "iselm1.lab";
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    /** 터미널 입력 한 번(붙여넣기 포함)의 최대 크기 */
    private static final int MAX_TEXT_MESSAGE_BYTES = 64 * 1024;

    private final DockerClient dockerClient;
    private final EbpfLogService ebpfLogService;
    private final LabProperties labProperties;

    private final AtomicInteger activeSessions = new AtomicInteger();
    private final Set<String> admittedSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> activeLabIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> sessionContainers = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLabIds = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> inputStreams = new ConcurrentHashMap<>();
    private final Map<String, ResultCallback.Adapter<Frame>> callbacks = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        rawSession.setTextMessageSizeLimit(MAX_TEXT_MESSAGE_BYTES);
        if (rawSession instanceof NativeWebSocketSession nws
                && nws.getNativeSession() instanceof jakarta.websocket.Session nativeSession) {
            // 입력이 없으면 연결을 끊고, 끊기면 컨테이너도 삭제된다.
            nativeSession.setMaxIdleTimeout(labProperties.session().idleTimeout().toMillis());
        }

        // Docker 출력 스레드와 요청 스레드가 동시에 보내도 안전하도록 감싼다.
        WebSocketSession session =
                new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        String sessionId = session.getId();

        String labId = session.getUri() == null ? null : UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("labId");

        if (!LabIds.isValid(labId)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("잘못된 labId"));
            return;
        }
        if (!activeLabIds.add(labId)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("이미 사용 중인 labId"));
            return;
        }
        sessionLabIds.put(sessionId, labId);

        if (activeSessions.incrementAndGet() > labProperties.session().maxSessions()) {
            activeSessions.decrementAndGet();
            cleanupSession(sessionId);
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason("동시 실습 수 초과, 잠시 후 다시 시도하세요"));
            return;
        }
        admittedSessions.add(sessionId);

        try {
            String image = labProperties.container().image();

            // 로컬에 이미지가 없을 때만 다운로드
            try {
                dockerClient.inspectImageCmd(image).exec();
            } catch (NotFoundException e) {
                session.sendMessage(new TextMessage("이미지 다운로드 중...\r\n"));
                dockerClient.pullImageCmd(image).start().awaitCompletion();
            }

            // 1. 리소스를 제한한 컨테이너 생성 및 시작
            CreateContainerResponse container = dockerClient.createContainerCmd(image)
                    .withCmd("tail", "-f", "/dev/null")
                    .withLabels(Map.of(LAB_LABEL, "true", LAB_LABEL + ".id", labId))
                    .withHostConfig(labHostConfig())
                    .exec();
            String containerId = container.getId();
            sessionContainers.put(sessionId, containerId);
            dockerClient.startContainerCmd(containerId).exec();

            // 2. eBPF 로그 스트리밍 시작
            ebpfLogService.startStreaming(labId, containerId);

            // 3. 컨테이너 셸(Exec) 연결
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withTty(true)
                    .withCmd("/bin/bash")
                    .exec();

            PipedOutputStream pipedOutputStream = new PipedOutputStream();
            PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream);
            inputStreams.put(sessionId, pipedOutputStream);

            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    if (session.isOpen()) {
                        try {
                            String output = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            session.sendMessage(new TextMessage(output));
                        } catch (IOException e) {
                            log.debug("터미널 출력 전송 실패 (session={})", sessionId, e);
                        }
                    }
                }
            };
            callbacks.put(sessionId, callback);

            dockerClient.execStartCmd(execCreateCmdResponse.getId())
                    .withDetach(false)
                    .withTty(true)
                    .withStdIn(pipedInputStream)
                    .exec(callback);

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("실습 컨테이너 시작 실패 (labId={})", labId, e);
            cleanupSession(sessionId);
            if (session.isOpen()) {
                // 내부 오류 내용(호스트 주소 등)은 클라이언트에 보내지 않는다.
                session.sendMessage(new TextMessage("실습 컨테이너를 시작하지 못했습니다.\r\n"));
                session.close(CloseStatus.SERVER_ERROR.withReason("컨테이너 시작 실패"));
            }
        }
    }

    private HostConfig labHostConfig() {
        LabProperties.Container limits = labProperties.container();
        long memoryBytes = limits.memoryMb() * 1024 * 1024;
        return HostConfig.newHostConfig()
                .withMemory(memoryBytes)
                .withMemorySwap(memoryBytes) // 스왑 사용 금지
                .withNanoCPUs((long) (limits.cpus() * 1_000_000_000L))
                .withPidsLimit(limits.pidsLimit()); // 포크 폭탄 방지
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        PipedOutputStream out = inputStreams.get(session.getId());
        if (out != null) {
            out.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanupSession(session.getId());
    }

    private void cleanupSession(String sessionId) {
        PipedOutputStream out = inputStreams.remove(sessionId);
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {}
        }

        ResultCallback.Adapter<Frame> callback = callbacks.remove(sessionId);
        if (callback != null) {
            try {
                callback.close();
            } catch (IOException ignored) {}
        }

        String containerId = sessionContainers.remove(sessionId);
        if (containerId != null) {
            removeContainer(containerId);
        }

        String labId = sessionLabIds.remove(sessionId);
        if (labId != null) {
            ebpfLogService.stopStreaming(labId);
            activeLabIds.remove(labId);
        }

        if (admittedSessions.remove(sessionId)) {
            activeSessions.decrementAndGet();
        }
    }

    private void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
        } catch (NotFoundException ignored) {
            // 이미 삭제됨
        } catch (Exception e) {
            log.warn("실습 컨테이너 삭제 실패 (container={})", containerId, e);
        }
    }

    /** 이전 실행에서 비정상 종료로 남은 실습 컨테이너를 정리한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void removeOrphanContainers() {
        try {
            List<Container> orphans = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(LAB_LABEL, "true"))
                    .exec();
            orphans.forEach(c -> removeContainer(c.getId()));
            if (!orphans.isEmpty()) {
                log.info("남아 있던 실습 컨테이너 {}개를 정리했습니다.", orphans.size());
            }
        } catch (Exception e) {
            log.warn("남은 실습 컨테이너 정리 실패 (Docker 호스트 연결 확인 필요): {}", e.getMessage());
        }
    }

    /** 서버 종료 시 실행 중인 실습 컨테이너를 모두 삭제한다. */
    @Override
    public void destroy() {
        sessionContainers.keySet().forEach(this::cleanupSession);
    }
}
