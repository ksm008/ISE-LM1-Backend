package iselm1.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DockerController {

    private final DockerClient dockerClient;

    @GetMapping("/start-container")
    public String startContainer() {
        String imageName = "arm64v8/alpine:latest";

        try {
            dockerClient.pullImageCmd(imageName).start().awaitCompletion();

            CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
                    .withCmd("sh", "-c", "while true; do sleep 3600; done")
                    .exec();

            dockerClient.startContainerCmd(container.getId()).exec();

            return "컨테이너 원격 생성 및 실행 성공! Container ID : " + container.getId();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "컨테이너 이미지 다운로드 중 에러 발생";
        } catch (Exception e) {
            return "컨테이너 실행 실패: " + e.getMessage();
        }
    }
}