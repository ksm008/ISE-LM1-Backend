package iselm1.websocket;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import iselm1.config.LabIds;
import iselm1.config.LabProperties;
import iselm1.docker.LabContainerSetup;
import iselm1.ebpf.EbpfLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends TextWebSocketHandler implements InitializingBean, DisposableBean {

    /** 이 서버가 만든 실습 컨테이너 표시. 남은 컨테이너 정리에 사용한다. */
    private static final String LAB_LABEL = "iselm1.lab";
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    /** 터미널 입력 한 번(붙여넣기 포함)의 최대 크기 */
    private static final int MAX_TEXT_MESSAGE_BYTES = 64 * 1024;
    private static final int MAX_TERMINAL_SIZE = 500;
    private static final long EXEC_START_TIMEOUT_SECONDS = 30;

    private final DockerClient dockerClient;
    private final EbpfLogService ebpfLogService;
    private final LabContainerSetup labContainerSetup;
    private final LabProperties labProperties;
    private final JsonMapper jsonMapper;

    private final AtomicInteger activeSessions = new AtomicInteger();
    private final Set<String> admittedSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> activeLabIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> sessionContainers = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLabIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionExecIds = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> inputStreams = new ConcurrentHashMap<>();
    private final Map<String, ResultCallback.Adapter<Frame>> callbacks = new ConcurrentHashMap<>();
    /** 셸 연결까지 끝난 세션 (ConcurrentWebSocketSessionDecorator) */
    private final Map<String, WebSocketSession> openSessions = new ConcurrentHashMap<>();
    /** 셸 연결까지 끝난 실습의 labId → 컨테이너 ID (패킷 캡처에서 사용) */
    private final Map<String, String> labContainers = new ConcurrentHashMap<>();
    /** 세션 만료 시각 (epoch millis) */
    private final Map<String, Long> leaseDeadlines = new ConcurrentHashMap<>();
    private final ScheduledExecutorService leaseSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lab-lease-sweeper");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void afterPropertiesSet() {
        leaseSweeper.scheduleWithFixedDelay(this::sweepExpiredSessions, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        rawSession.setTextMessageSizeLimit(MAX_TEXT_MESSAGE_BYTES);
        if (rawSession instanceof NativeWebSocketSession nws
                && nws.getNativeSession() instanceof jakarta.websocket.Session nativeSession) {
            // 세션 만료는 sweepExpiredSessions 가 처리한다. 이것은 응답 없는 연결을 끊기 위한 안전장치다.
            nativeSession.setMaxIdleTimeout(labProperties.session().lease().plusMinutes(1).toMillis());
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
                sendOutput(session, "이미지 다운로드 중...\r\n");
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

            // 2. 사용자 셸을 붙이기 전에 준비 작업 (이미지 초기화, apt 패키지 설치 차단)
            labContainerSetup.apply(containerId);

            // 3. eBPF 로그 스트리밍 시작 (SSE 구독이 있을 때만)
            ebpfLogService.startStreaming(labId, containerId);

            // 4. 컨테이너 셸(Exec) 연결
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
                            sendOutput(session, new String(frame.getPayload(), StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            log.debug("터미널 출력 전송 실패 (session={})", sessionId, e);
                        }
                    }
                }

                // 셸이 끝나면(exit 등) 연결도 닫는다. 닫히면 afterConnectionClosed 에서 컨테이너를 정리한다.
                // 콜백 스레드는 super.onComplete()/onError() 에서 인터럽트되어 close 프레임을 보내지 못하므로
                // 다른 스레드에서 닫는다.
                @Override
                public void onComplete() {
                    super.onComplete();
                    leaseSweeper.execute(() ->
                            closeQuietly(session, CloseStatus.NORMAL.withReason("셸이 종료되었습니다")));
                }

                @Override
                public void onError(Throwable throwable) {
                    super.onError(throwable);
                    log.debug("셸 스트림 오류 (session={})", sessionId, throwable);
                    leaseSweeper.execute(() ->
                            closeQuietly(session, CloseStatus.SERVER_ERROR.withReason("셸 연결이 끊어졌습니다")));
                }
            };
            callbacks.put(sessionId, callback);

            String execId = execCreateCmdResponse.getId();
            dockerClient.execStartCmd(execId)
                    .withDetach(false)
                    .withTty(true)
                    .withStdIn(pipedInputStream)
                    .exec(callback);

            // 셸이 실제로 붙은 뒤에 입력·크기 조절 메시지를 처리하도록 기다린다.
            if (!callback.awaitStarted(EXEC_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("셸 연결 시간 초과");
            }
            sessionExecIds.put(sessionId, execId);

            // 5. 세션 유지 시간 시작
            openSessions.put(sessionId, session);
            labContainers.put(labId, containerId);
            renewLease(session);

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("실습 컨테이너 시작 실패 (labId={})", labId, e);
            cleanupSession(sessionId);
            if (session.isOpen()) {
                // 내부 오류 내용(호스트 주소 등)은 클라이언트에 보내지 않는다.
                sendOutput(session, "실습 컨테이너를 시작하지 못했습니다.\r\n");
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
                .withPidsLimit(limits.pidsLimit()) // 포크 폭탄 방지
                // 실습에 필요한 추가 권한 (기본: SYS_PACCT = 프로세스 accounting, lastcomm 용)
                .withCapAdd(limits.capAdd().stream().map(Capability::valueOf).toArray(Capability[]::new));
    }

    /**
     * 클라이언트 메시지는 JSON 이다.
     * <ul>
     *   <li>{@code {"type":"input","data":"ls\r"}} : 셸 입력</li>
     *   <li>{@code {"type":"resize","cols":120,"rows":30}} : 터미널 크기 변경</li>
     *   <li>{@code {"type":"renew"}} : 세션 유지 시간 갱신</li>
     * </ul>
     * 형식이 맞지 않는 메시지는 무시한다.
     * <p>
     * 서버 메시지도 JSON 이다: {@code {"type":"output","data":"..."}}, {@code {"type":"session","remainingSeconds":600}}
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node;
        try {
            node = jsonMapper.readTree(message.getPayload());
        } catch (JacksonException e) {
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }

        switch (node.path("type").asString("")) {
            case "input" -> {
                JsonNode data = node.get("data");
                PipedOutputStream out = inputStreams.get(session.getId());
                if (out != null && data != null && data.isString()) {
                    out.write(data.asString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            case "resize" -> resizeTerminal(session.getId(), node.path("cols").asInt(0), node.path("rows").asInt(0));
            case "renew" -> {
                // 전송은 동시 전송에 안전한 decorator 로 한다.
                WebSocketSession decorated = openSessions.get(session.getId());
                if (decorated != null) {
                    renewLease(decorated);
                }
            }
            default -> { /* 알 수 없는 type 은 무시 */ }
        }
    }

    /** 지금부터 lease 만큼 세션을 연장하고, 남은 시간을 클라이언트에 알린다. */
    private void renewLease(WebSocketSession session) throws IOException {
        Duration lease = labProperties.session().lease();
        leaseDeadlines.put(session.getId(), System.currentTimeMillis() + lease.toMillis());
        sendJson(session, Map.of("type", "session", "remainingSeconds", lease.toSeconds()));
    }

    private void sweepExpiredSessions() {
        long now = System.currentTimeMillis();
        leaseDeadlines.forEach((sessionId, deadline) -> {
            if (deadline > now || !leaseDeadlines.remove(sessionId, deadline)) {
                return;
            }
            WebSocketSession session = openSessions.get(sessionId);
            if (session == null) {
                return;
            }
            try {
                sendOutput(session, "\r\n세션 시간이 만료되어 실습을 종료합니다.\r\n");
            } catch (IOException | IllegalStateException e) {
                log.debug("만료 안내 전송 실패 (session={})", sessionId, e);
            }
            // 닫히면 afterConnectionClosed 에서 컨테이너를 정리한다.
            closeQuietly(session, CloseStatus.NORMAL.withReason("세션 시간 만료"));
        });
    }

    private void sendOutput(WebSocketSession session, String data) throws IOException {
        sendJson(session, Map.of("type", "output", "data", data));
    }

    private void sendJson(WebSocketSession session, Map<String, ?> message) throws IOException {
        session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException | IllegalStateException e) {
            log.debug("WebSocket 종료 실패 (session={})", session.getId(), e);
        }
    }

    private void resizeTerminal(String sessionId, int cols, int rows) {
        String execId = sessionExecIds.get(sessionId);
        if (execId == null || cols < 1 || rows < 1 || cols > MAX_TERMINAL_SIZE || rows > MAX_TERMINAL_SIZE) {
            return;
        }
        try {
            dockerClient.resizeExecCmd(execId).withSize(rows, cols).exec();
        } catch (Exception e) {
            log.debug("터미널 크기 변경 실패 (session={})", sessionId, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanupSession(session.getId());
    }

    /** 실행 중인(셸 연결까지 끝난) 실습의 컨테이너 ID. 없으면 null. */
    public String containerIdOf(String labId) {
        return labId == null ? null : labContainers.get(labId);
    }

    private void cleanupSession(String sessionId) {
        // 컨테이너를 지우기 전에 먼저 빼서, 캡처 쪽이 종료 원인을 "실습 종료"로 알 수 있게 한다.
        String runningLabId = sessionLabIds.get(sessionId);
        if (runningLabId != null) {
            labContainers.remove(runningLabId, sessionContainers.get(sessionId));
        }
        openSessions.remove(sessionId);
        leaseDeadlines.remove(sessionId);
        sessionExecIds.remove(sessionId);
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
        leaseSweeper.shutdownNow();
        sessionContainers.keySet().forEach(this::cleanupSession);
    }
}
