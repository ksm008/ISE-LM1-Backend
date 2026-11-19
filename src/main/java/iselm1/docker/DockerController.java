package iselm1.docker;

import com.github.dockerjava.api.DockerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 실습 서버의 Docker 연결 상태 확인용 API.
 * 컨테이너를 만들지 않고 Docker Engine 에 ping 만 보낸다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DockerController {

    private final DockerClient dockerClient;

    @GetMapping("/api/docker/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            dockerClient.pingCmd().exec();
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception e) {
            log.warn("Docker 연결 확인 실패", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
        }
    }
}
