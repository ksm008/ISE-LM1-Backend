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
        @DefaultValue Session session,
        @DefaultValue Capture capture
) {

    /** 패킷 캡처 (Wireshark 실습) */
    public record Capture(
            /** 패킷 하나에서 캡처하는 최대 바이트 (tcpdump -s) */
            @DefaultValue("4096") int snaplen,
            /** 캡처 한 번에 받는 최대 패킷 수. 넘으면 캡처를 중지한다. */
            @DefaultValue("20000") int maxPackets
    ) {}

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
            /**
             * 이 백엔드를 구분하는 이름. 만든 실습 컨테이너에 라벨(iselm1.lab.instance)로 붙이고,
             * 시작할 때는 이 이름의 컨테이너만 정리한다. 같은 Docker 호스트를 쓰는 다른 백엔드(배포 서버 ↔ 개발 PC)의
             * 실습 컨테이너를 지우지 않기 위해서다. 비우면 컴퓨터 이름(hostname)을 쓴다.
             */
            String instance,
            @DefaultValue("sec-lab-ubuntu:1.2") String image,
            @DefaultValue("512") long memoryMb,
            @DefaultValue("1.0") double cpus,
            @DefaultValue("256") long pidsLimit,
            /** true 이면 실습 컨테이너에서 apt 로 패키지를 설치하지 못하게 한다 (sudo 사용 포함). */
            @DefaultValue("true") boolean blockPackageInstall,
            /**
             * 컨테이너에 추가할 Linux capability (Docker 기본값에 더해서).
             * SYS_PACCT: 프로세스 accounting(accton) 사용. PID 네임스페이스별로 동작해 호스트 프로세스는 기록되지 않는다.
             */
            @DefaultValue("SYS_PACCT") List<String> capAdd
    ) {}

    public record Session(
            @DefaultValue("10") int maxSessions,
            /** 실습 세션 유지 시간. 갱신(renew) 요청을 받으면 그 시점부터 다시 이 시간만큼 연장한다. */
            @DefaultValue("10m") Duration lease,
            @DefaultValue("30m") Duration sseTimeout
    ) {}
}
