package iselm1.docker;

import com.github.dockerjava.api.DockerClient;
import iselm1.config.LabProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 실습 컨테이너를 시작한 직후, 사용자 셸을 붙이기 전에 root 로 실행하는 준비 작업.
 * <ol>
 *   <li>이미지의 초기화 스크립트 {@code /usr/local/sbin/lab-init} 실행 (rsyslog, 프로세스 accounting 시작 등).
 *       없거나 실패해도 실습은 시작한다.</li>
 *   <li>apt 패키지 설치 차단 (lab.container.block-package-install). 실패하면 실습을 시작하지 않는다.</li>
 * </ol>
 * 실습 사용자는 sudo 로 root 가 될 수 있다. 차단 스크립트는 apt 바이너리와 다운로드 방법을 지워서
 * root 가 되어도 apt 로는 설치할 수 없게 한다. 단, 인터넷에 접근할 수 있는 한
 * root 가 curl 등으로 파일을 직접 받아 설치하는 것까지 막지는 못한다.
 */
@Slf4j
@Component
public class LabContainerSetup {

    private static final String BLOCK_SCRIPT_PATH = "lab/block-package-install.sh";
    private static final String IMAGE_INIT = "/usr/local/sbin/lab-init";
    private static final long TIMEOUT_SECONDS = 30;

    private final DockerClient dockerClient;
    private final boolean blockPackageInstall;
    private final String blockScript;

    public LabContainerSetup(DockerClient dockerClient, LabProperties labProperties) {
        this.dockerClient = dockerClient;
        this.blockPackageInstall = labProperties.container().blockPackageInstall();
        this.blockScript = loadScript();
    }

    private static String loadScript() {
        try (InputStream in = new ClassPathResource(BLOCK_SCRIPT_PATH).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(BLOCK_SCRIPT_PATH + " 을 읽지 못했습니다.", e);
        }
    }

    /** 준비 작업을 실행한다. 패키지 설치 차단에 실패하면 예외를 던진다 (제한 없이 실습을 시작하지 않는다). */
    public void apply(String containerId) throws InterruptedException {
        runImageInit(containerId);

        if (blockPackageInstall) {
            DockerExecs.Result result = DockerExecs.run(dockerClient, containerId, "root", TIMEOUT_SECONDS,
                    "sh", "-c", blockScript);
            if (!result.succeeded()) {
                throw new IllegalStateException(
                        "패키지 설치 차단 설정 실패 (exit=" + result.exitCode() + "): " + result.output().strip());
            }
        }
    }

    private void runImageInit(String containerId) throws InterruptedException {
        try {
            DockerExecs.Result result = DockerExecs.run(dockerClient, containerId, "root", TIMEOUT_SECONDS,
                    "sh", "-c", "[ -x " + IMAGE_INIT + " ] || exit 0; exec " + IMAGE_INIT);
            if (!result.succeeded() || !result.output().isBlank()) {
                log.warn("실습 이미지 초기화 경고 (container={}, exit={}): {}",
                        containerId, result.exitCode(), result.output().strip());
            }
        } catch (RuntimeException e) {
            log.warn("실습 이미지 초기화 실패 (container={})", containerId, e);
        }
    }
}
