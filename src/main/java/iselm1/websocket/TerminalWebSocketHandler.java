package iselm1.websocket;

import iselm1.ebpf.EbpfLogService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final String IMAGE_NAME = "sec-lab-ubuntu:1.0";

    private final DockerClient dockerClient;
    private final EbpfLogService ebpfLogService;
    private final Map<String, String> sessionContainers = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLabIds = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> inputStreams = new ConcurrentHashMap<>();
    private final Map<String, ResultCallback.Adapter<Frame>> callbacks = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            // 로컬에 이미지가 없을 때만 다운로드
            try {
                dockerClient.inspectImageCmd(IMAGE_NAME).exec();
            } catch (NotFoundException e) {
                session.sendMessage(new TextMessage("이미지 다운로드 중...\r\n"));
                dockerClient.pullImageCmd(IMAGE_NAME).start().awaitCompletion();
            }

            // 1. 컨테이너 생성 및 시작
            CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE_NAME)
                    .withCmd("tail", "-f", "/dev/null")
                    .exec();
            String containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();
            sessionContainers.put(session.getId(), containerId);

            // 2. eBPF 로그 스트리밍 시작
            String labId = UriComponentsBuilder.fromUri(session.getUri())
                    .build()
                    .getQueryParams()
                    .getFirst("labId");

            if (labId != null && !labId.isBlank()) {
                sessionLabIds.put(session.getId(), labId);
                ebpfLogService.startStreaming(labId, containerId);
            }

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
            inputStreams.put(session.getId(), pipedOutputStream);

            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    if (session.isOpen()) {
                        try {
                            String output = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            session.sendMessage(new TextMessage(output));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            };
            callbacks.put(session.getId(), callback);

            dockerClient.execStartCmd(execCreateCmdResponse.getId())
                    .withDetach(false)
                    .withTty(true)
                    .withStdIn(pipedInputStream)
                    .exec(callback);

        } catch (Exception e) {
            cleanupSession(session.getId());
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("컨테이너 시작 실패: " + e.getMessage() + "\r\n"));
                session.close(CloseStatus.SERVER_ERROR);
            }
        }
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
            try {
                dockerClient.removeContainerCmd(containerId)
                        .withForce(true)
                        .exec();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        String labId = sessionLabIds.remove(sessionId);
        if (labId != null) {
            ebpfLogService.stopStreaming(labId);
        }
    }
}