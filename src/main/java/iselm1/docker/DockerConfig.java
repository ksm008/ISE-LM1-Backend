package iselm1.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import iselm1.config.LabProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DockerConfig {

    @Bean
    public DockerClient dockerClient(LabProperties labProperties) {
        LabProperties.Docker docker = labProperties.docker();

        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(docker.host())
                .withDockerTlsVerify(docker.tlsVerify());
        if (docker.certPath() != null && !docker.certPath().isBlank()) {
            builder.withDockerCertPath(docker.certPath());
        }
        DockerClientConfig config = builder.build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                // responseTimeout 은 지정하지 않는다. 지정하면 쉘(exec) 출력 스트림에도 소켓 읽기 제한으로 적용되어,
                // 그 시간 동안 출력이 없으면 스트림이 끊겨 터미널이 응답하지 않는다.
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }
}
