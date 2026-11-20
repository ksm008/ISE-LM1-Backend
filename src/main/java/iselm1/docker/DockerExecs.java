package iselm1.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 컨테이너 안에서 짧은 명령을 실행하고 끝날 때까지 기다린다. */
public final class DockerExecs {

    private DockerExecs() {}

    public record Result(long exitCode, String output) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    /**
     * 명령을 실행하고 종료 코드와 출력(stdout + stderr)을 돌려준다.
     * cmd 는 셸을 거치지 않고 그대로 전달된다.
     */
    public static Result run(DockerClient dockerClient, String containerId, String user, long timeoutSeconds,
                             String... cmd) throws InterruptedException {
        String execId = dockerClient.execCreateCmd(containerId)
                .withUser(user)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd(cmd)
                .exec()
                .getId();

        StringBuffer output = new StringBuffer();
        ResultCallback.Adapter<Frame> callback = dockerClient.execStartCmd(execId)
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                });
        try {
            if (!callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("컨테이너 명령 실행 시간 초과: " + cmd[0]);
            }
        } finally {
            try {
                callback.close();
            } catch (IOException ignored) {}
        }

        // 출력 스트림이 끝난 직후에는 아직 종료 코드가 기록되지 않았을 수 있다.
        for (int i = 0; i < 20; i++) {
            InspectExecResponse inspect = dockerClient.inspectExecCmd(execId).exec();
            if (!Boolean.TRUE.equals(inspect.isRunning()) && inspect.getExitCodeLong() != null) {
                return new Result(inspect.getExitCodeLong(), output.toString());
            }
            Thread.sleep(50);
        }
        return new Result(-1, output.toString());
    }
}
