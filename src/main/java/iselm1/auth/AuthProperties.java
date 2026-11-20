package iselm1.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 회원 기능 설정 (auth.*). 관리자 계정, 메일, 구글 정보는 git 에 없는 application-local.properties 에 둔다.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        /** 구글 로그인 후 돌아갈 프론트엔드 주소 */
        @DefaultValue("http://localhost:5173") String frontendUrl,
        @DefaultValue Admin admin,
        @DefaultValue Mail mail,
        @DefaultValue Google google,
        @DefaultValue Verification verification,
        @DefaultValue Login login
) {

    /** 서버 시작 시 만들거나 맞추는 관리자 계정. 비어 있으면 건너뛴다. */
    public record Admin(String username, String password, String email) {
        public boolean configured() {
            return notBlank(username) && notBlank(password);
        }
    }

    /** 보낸 사람 주소. 비우면 spring.mail.username */
    public record Mail(String from) {}

    public record Google(String clientId, String clientSecret) {
        public boolean configured() {
            return notBlank(clientId) && notBlank(clientSecret);
        }
    }

    public record Verification(
            @DefaultValue("10m") Duration codeTtl,
            @DefaultValue("60s") Duration resendCooldown,
            @DefaultValue("5") int maxAttempts
    ) {}

    /** 같은 아이디로 maxFailures 번 연속 실패하면 lockDuration 동안 로그인을 막는다 */
    public record Login(
            @DefaultValue("5") int maxFailures,
            @DefaultValue("5m") Duration lockDuration
    ) {}

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
