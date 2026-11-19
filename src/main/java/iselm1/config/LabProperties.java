package iselm1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 실습 서버 연결 정보와 실습 세션 제한 설정.
 * 값은 application.properties, application-local.properties 또는 환경 변수(LAB_DOCKER_HOST 등)로 지정한다.
 */
@ConfigurationProperties(prefix = "lab")
public record LabProperties(
        @DefaultValue Docker docker,
        @DefaultValue Ebpf ebpf,
        @DefaultValue Cors cors,
        @DefaultValue Container container,
        @DefaultValue Session session
) {

    public record Docker(
            @DefaultValue("tcp://localhost:2375") String host,
            @DefaultValue("false") boolean tlsVerify,
            String certPath
    ) {}

    public record Ebpf(
            @DefaultValue("http://localhost:9000") String agentUrl
    ) {}

    public record Cors(
            @DefaultValue("http://localhost:5173") List<String> allowedOrigins
    ) {}

    public record Container(
            @DefaultValue("sec-lab-ubuntu:1.0") String image,
            @DefaultValue("512") long memoryMb,
            @DefaultValue("1.0") double cpus,
            @DefaultValue("256") long pidsLimit
    ) {}

    public record Session(
            @DefaultValue("10") int maxSessions,
            /** 실습 세션 유지 시간. 갱신(renew) 요청을 받으면 그 시점부터 다시 이 시간만큼 연장한다. */
            @DefaultValue("10m") Duration lease,
            @DefaultValue("30m") Duration sseTimeout
    ) {}
}
