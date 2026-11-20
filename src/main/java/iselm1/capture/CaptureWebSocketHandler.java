package iselm1.capture;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import iselm1.auth.AuthUser;
import iselm1.config.LabIds;
import iselm1.config.LabProperties;
import iselm1.docker.DockerExecs;
import iselm1.websocket.TerminalWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 패킷 캡처 WebSocket ({@code /ws/capture?labId=}). Wireshark 실습 화면이 사용한다.
 * <p>
 * 실행 중인 터미널 실습 컨테이너 안에서 root 로 tcpdump 를 실행하고, pcap 출력을 패킷 단위로 나눠
 * 브라우저로 보낸다. 프로토콜 해석은 브라우저에서 한다. 연결은 labId 하나당 하나이며,
 * 같은 labId 로 새 연결이 오면 이전 연결을 닫고 새 연결이 이어받는다.
 * <ul>
 *   <li>클라이언트 → 서버: {@code {"type":"start","interface":"any","filter":"icmp"}}, {@code {"type":"stop"}}</li>
 *   <li>서버 → 클라이언트: {@code ready}(인터페이스 목록), {@code started}(linkType), {@code packets}, {@code stopped}, {@code error}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaptureWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private static final Pattern INTERFACE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,14}");
    private static final int MAX_FILTER_LENGTH = 512;
    private static final int MAX_CLIENT_MESSAGE_BYTES = 4 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 8 * 1024 * 1024;
    /** 보낼 데이터가 이만큼 밀려 있으면 이번 전송을 건너뛴다 (브라우저가 느릴 때) */
    private static final int SEND_BACKLOG_BYTES = SEND_BUFFER_LIMIT_BYTES / 2;
    private static final long FLUSH_INTERVAL_MS = 200;
    /** 메시지 하나에 담는 패킷 데이터 양 (대략) */
    private static final int MAX_BATCH_BYTES = 256 * 1024;
    private static final int MAX_STDERR_CHARS = 4 * 1024;
    private static final long START_TIMEOUT_SECONDS = 10;
    private static final long KILL_TIMEOUT_SECONDS = 5;

    /** $0 = pid 파일, $@ = tcpdump 인자. 인자는 셸이 해석하지 않는다. */
    private static final String TCPDUMP_SCRIPT = "echo $$ > \"$0\" && exec tcpdump \"$@\"";
    private static final String KILL_SCRIPT = "[ -f \"$0\" ] && kill \"$(cat \"$0\")\" 2>/dev/null; rm -f \"$0\"";

    private final DockerClient dockerClient;
    private final TerminalWebSocketHandler terminalHandler;
    private final LabProperties labProperties;
    private final JsonMapper jsonMapper;

    /** labId → 현재 캡처 연결 (labId 당 하나, 새 연결이 오면 이전 연결을 닫는다) */
    private final Map<String, Connection> labConnections = new ConcurrentHashMap<>();
    /** WebSocket 세션 ID → 연결 */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "lab-capture");
        t.setDaemon(true);
        return t;
    });

    /** 브라우저 연결 하나 */
    private static final class Connection {
        final WebSocketSession session;
        final String labId;
        Capture capture; // this 로 보호

        Connection(WebSocketSession session, String labId) {
            this.session = session;
            this.labId = labId;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        rawSession.setTextMessageSizeLimit(MAX_CLIENT_MESSAGE_BYTES);
        WebSocketSession session =
                new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);

        String labId = session.getUri() == null ? null : UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("labId");
        if (!LabIds.isValid(labId)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("잘못된 labId"));
            return;
        }
        String containerId = terminalHandler.containerIdOf(labId);
        if (containerId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("실행 중인 터미널 실습이 없습니다"));
            return;
        }
        // 자기 실습만 캡처할 수 있다 (labId 를 알아도 다른 사람의 실습에는 붙을 수 없음)
        if (!terminalHandler.isOwner(labId, AuthUser.idOf(rawSession.getPrincipal()))) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("이 실습을 캡처할 권한이 없습니다"));
            return;
        }
        // 같은 실습의 캡처 연결은 하나만 둔다. 새 연결을 받고 이전 연결은 닫는다.
        // (새로고침, 다른 탭, 개발 모드 React StrictMode 의 이중 연결에서 마지막 연결이 이어받게 한다)
        Connection conn = new Connection(session, labId);
        connections.put(rawSession.getId(), conn);
        Connection previous = labConnections.put(labId, conn);
        if (previous != null) {
            stopCurrent(previous, "다른 창에서 캡처 화면을 열었습니다.");
            closeQuietly(previous.session, CloseStatus.NORMAL.withReason("다른 창에서 캡처 화면을 열었습니다"));
        }

        LabProperties.Capture limits = labProperties.capture();
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("type", "ready");
        ready.put("interfaces", listInterfaces(containerId));
        ready.put("snaplen", limits.snaplen());
        ready.put("maxPackets", limits.maxPackets());
        sendJson(session, ready);
    }

    /** 컨테이너의 네트워크 인터페이스 목록. 맨 앞은 모든 인터페이스(any). */
    private List<String> listInterfaces(String containerId) {
        Set<String> names = new TreeSet<>();
        try {
            DockerExecs.Result result = DockerExecs.run(dockerClient, containerId, "root", 5, "ls", "/sys/class/net");
            if (result.succeeded()) {
                Arrays.stream(result.output().split("\\s+"))
                        .filter(name -> INTERFACE_NAME.matcher(name).matches())
                        .forEach(names::add);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("인터페이스 목록 조회 실패 (container={})", containerId, e);
        }
        if (names.isEmpty()) {
            names.addAll(List.of("eth0", "lo"));
        }
        List<String> list = new ArrayList<>();
        list.add("any");
        list.addAll(names);
        return list;
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) throws Exception {
        Connection conn = connections.get(rawSession.getId());
        if (conn == null) {
            return;
        }
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
            case "start" -> {
                String iface = node.path("interface").asString("any").strip();
                String filter = node.path("filter").asString("").strip();
                String problem = validate(iface, filter);
                if (problem != null) {
                    sendError(conn.session, problem);
                    return;
                }
                stopCurrent(conn, "새 캡처를 시작합니다.");
                startCapture(conn, iface, filter);
            }
            case "stop" -> stopCurrent(conn, "캡처를 중지했습니다.");
            default -> { /* 알 수 없는 type 은 무시 */ }
        }
    }

    private static String validate(String iface, String filter) {
        if (!"any".equals(iface) && !INTERFACE_NAME.matcher(iface).matches()) {
            return "인터페이스 이름이 올바르지 않습니다.";
        }
        if (filter.length() > MAX_FILTER_LENGTH) {
            return "캡처 필터가 너무 깁니다 (최대 " + MAX_FILTER_LENGTH + "자).";
        }
        if (filter.startsWith("-") || filter.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "캡처 필터에 사용할 수 없는 문자가 있습니다.";
        }
        return null;
    }

    private void stopCurrent(Connection conn, String reason) {
        Capture current;
        synchronized (conn) {
            current = conn.capture;
        }
        if (current != null) {
            current.stop(reason, false);
        }
    }

    private void startCapture(Connection conn, String iface, String filter) throws IOException {
        String containerId = terminalHandler.containerIdOf(conn.labId);
        if (containerId == null) {
            closeQuietly(conn.session, CloseStatus.NORMAL.withReason("실습이 종료되었습니다"));
            return;
        }
        Capture capture = new Capture(conn, containerId, iface, filter);
        synchronized (conn) {
            conn.capture = capture;
        }
        try {
            capture.begin();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("패킷 캡처 시작 실패 (labId={})", conn.labId, e);
            capture.stop("캡처를 시작하지 못했습니다.", true);
        }
    }

    /** 컨테이너 안에서 실행 중인 tcpdump 하나 */
    private final class Capture implements PcapStreamParser.Listener {
        private final Connection conn;
        private final String containerId;
        private final String iface;
        private final String filter;
        private final String pidFile = "/run/iselm1-capture-" + UUID.randomUUID() + ".pid";
        private final int maxPackets = labProperties.capture().maxPackets();
        private final PcapStreamParser parser;

        private final Queue<Map<String, Object>> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger packetCount = new AtomicInteger();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final StringBuffer stderr = new StringBuffer();
        private volatile int linkType = -1;
        private volatile boolean limitReached;
        private boolean announced; // this 로 보호
        private ResultCallback.Adapter<Frame> callback;
        private ScheduledFuture<?> flusher;

        Capture(Connection conn, String containerId, String iface, String filter) {
            this.conn = conn;
            this.containerId = containerId;
            this.iface = iface;
            this.filter = filter;
            // 레코드 헤더 길이가 snaplen 을 크게 넘으면 형식 오류로 본다
            this.parser = new PcapStreamParser(Math.max(labProperties.capture().snaplen(), 262_144), this);
        }

        void begin() throws InterruptedException {
            List<String> cmd = new ArrayList<>(List.of(
                    "sh", "-c", TCPDUMP_SCRIPT, pidFile,
                    "-i", iface,
                    "-n",  // 이름 조회(DNS)로 캡처 트래픽이 생기지 않게 한다
                    "-U",  // 패킷마다 바로 출력
                    "-s", String.valueOf(labProperties.capture().snaplen()),
                    "-w", "-"));
            if (!filter.isEmpty()) {
                cmd.add("--"); // 필터가 옵션으로 해석되지 않게 한다
                cmd.add(filter);
            }

            String execId = dockerClient.execCreateCmd(containerId)
                    .withUser("root")
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false) // pcap 바이너리가 TTY 변환으로 깨지지 않게 한다
                    .withCmd(cmd.toArray(String[]::new))
                    .exec()
                    .getId();

            callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    if (stopped.get()) {
                        return;
                    }
                    switch (frame.getStreamType()) {
                        case STDOUT, RAW -> {
                            try {
                                parser.feed(frame.getPayload());
                            } catch (RuntimeException e) {
                                log.debug("pcap 해석 실패 (labId={})", conn.labId, e);
                                scheduler.execute(() -> stop("캡처 데이터를 해석하지 못해 중지했습니다.", true));
                            }
                        }
                        case STDERR -> {
                            if (stderr.length() < MAX_STDERR_CHARS) {
                                stderr.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                        default -> { }
                    }
                }

                // 콜백 스레드는 super 호출 뒤 인터럽트되어 WebSocket 으로 보내지 못하므로 다른 스레드에서 처리한다.
                @Override
                public void onComplete() {
                    super.onComplete();
                    scheduler.execute(Capture.this::handleExit);
                }

                @Override
                public void onError(Throwable throwable) {
                    super.onError(throwable);
                    log.debug("캡처 스트림 오류 (labId={})", conn.labId, throwable);
                    scheduler.execute(Capture.this::handleExit);
                }
            };
            dockerClient.execStartCmd(execId).withDetach(false).withTty(false).exec(callback);
            if (!callback.awaitStarted(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tcpdump 시작 시간 초과");
            }
            flusher = scheduler.scheduleWithFixedDelay(this::flushQuietly,
                    FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onHeader(int linkType) {
            this.linkType = linkType;
        }

        @Override
        public void onPacket(long seconds, long micros, int originalLength, byte[] data) {
            int number = packetCount.incrementAndGet();
            if (number > maxPackets) {
                if (!limitReached) {
                    limitReached = true;
                    scheduler.execute(() ->
                            stop("최대 패킷 수(" + maxPackets + "개)에 도달해 캡처를 중지했습니다.", false));
                }
                return;
            }
            Map<String, Object> packet = new LinkedHashMap<>();
            packet.put("no", number);
            packet.put("s", seconds);
            packet.put("us", micros);
            packet.put("len", originalLength);
            packet.put("data", Base64.getEncoder().encodeToString(data));
            queue.add(packet);
        }

        /** tcpdump 가 끝났을 때 (필터 오류, 실습 종료, 중지 후 종료 등) */
        private void handleExit() {
            if (stopped.get()) {
                return;
            }
            String message = stderrSummary();
            if (terminalHandler.containerIdOf(conn.labId) == null) {
                stop("터미널 실습이 종료되어 캡처를 중지했습니다.", false);
                closeQuietly(conn.session, CloseStatus.NORMAL.withReason("실습이 종료되었습니다"));
            } else if (linkType < 0) {
                stop("캡처를 시작하지 못했습니다." + (message.isEmpty() ? "" : " " + message), true);
            } else {
                stop("캡처 프로그램이 종료되었습니다." + (message.isEmpty() ? "" : " " + message), true);
            }
        }

        /** tcpdump 안내 문구를 뺀 오류 메시지 */
        private String stderrSummary() {
            return stderr.toString().lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("tcpdump: listening on"))
                    .filter(line -> !line.startsWith("tcpdump: data link type"))
                    .filter(line -> !line.matches("\\d+ packets? (captured|received by filter|dropped by kernel)"))
                    .reduce((a, b) -> a + " / " + b)
                    .orElse("");
        }

        void stop(String reason, boolean error) {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            if (flusher != null) {
                flusher.cancel(false);
            }
            killTcpdump();
            if (callback != null) {
                try {
                    callback.close();
                } catch (IOException ignored) {}
            }

            synchronized (this) {
                flushQuietly();
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("type", "stopped");
                message.put("reason", reason);
                message.put("error", error);
                message.put("packets", Math.min(packetCount.get(), maxPackets));
                sendQuietly(message);
            }
            synchronized (conn) {
                if (conn.capture == this) {
                    conn.capture = null;
                }
            }
        }

        private void killTcpdump() {
            try {
                DockerExecs.run(dockerClient, containerId, "root", KILL_TIMEOUT_SECONDS, "sh", "-c", KILL_SCRIPT, pidFile);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 실습이 끝나 컨테이너가 이미 삭제된 경우 등
                log.debug("tcpdump 종료 실패 (container={})", containerId, e);
            }
        }

        private synchronized void flushQuietly() {
            WebSocketSession session = conn.session;
            if (!session.isOpen()) {
                return;
            }
            if (session instanceof ConcurrentWebSocketSessionDecorator decorator
                    && decorator.getBufferSize() > SEND_BACKLOG_BYTES) {
                return; // 브라우저가 따라오지 못하고 있다. 다음에 보낸다.
            }
            if (!announced) {
                if (linkType < 0) {
                    return;
                }
                Map<String, Object> started = new LinkedHashMap<>();
                started.put("type", "started");
                started.put("linkType", linkType);
                started.put("interface", iface);
                started.put("filter", filter);
                sendQuietly(started);
                announced = true;
            }

            List<Map<String, Object>> batch = new ArrayList<>();
            int bytes = 0;
            Map<String, Object> packet;
            while ((packet = queue.poll()) != null) {
                batch.add(packet);
                bytes += ((String) packet.get("data")).length() + 64;
                if (bytes >= MAX_BATCH_BYTES) {
                    sendQuietly(Map.of("type", "packets", "packets", batch));
                    batch = new ArrayList<>();
                    bytes = 0;
                }
            }
            if (!batch.isEmpty()) {
                sendQuietly(Map.of("type", "packets", "packets", batch));
            }
        }

        private void sendQuietly(Map<String, ?> message) {
            try {
                if (conn.session.isOpen()) {
                    sendJson(conn.session, message);
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("캡처 메시지 전송 실패 (labId={})", conn.labId, e);
            }
        }
    }

    private void sendError(WebSocketSession session, String message) throws IOException {
        sendJson(session, Map.of("type", "error", "message", message));
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

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        Connection conn = connections.remove(rawSession.getId());
        if (conn == null) {
            return;
        }
        stopCurrent(conn, "연결이 종료되었습니다.");
        // 이미 새 연결로 바뀌었으면 그대로 둔다
        labConnections.remove(conn.labId, conn);
    }

    @Override
    public void destroy() {
        connections.values().forEach(conn -> stopCurrent(conn, "서버를 종료합니다."));
        scheduler.shutdownNow();
    }
}
