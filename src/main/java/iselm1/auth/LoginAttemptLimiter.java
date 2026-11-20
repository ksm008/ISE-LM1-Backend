package iselm1.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비밀번호 무차별 대입을 늦춘다. 같은 아이디로 연속 maxFailures 번 실패하면 lockDuration 동안 로그인을 막는다.
 * 서버 메모리에만 기록한다 (재시작하면 초기화).
 */
@Component
public class LoginAttemptLimiter {

    private record State(int failures, Instant lockedUntil) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lockDuration;
    private final Clock clock;

    @Autowired
    public LoginAttemptLimiter(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginAttemptLimiter(AuthProperties properties, Clock clock) {
        this.maxFailures = properties.login().maxFailures();
        this.lockDuration = properties.login().lockDuration();
        this.clock = clock;
    }

    /** 잠겨 있으면 남은 초, 아니면 0 */
    public long lockedSeconds(String username) {
        State state = states.get(key(username));
        if (state == null || state.lockedUntil() == null) {
            return 0;
        }
        long seconds = Duration.between(clock.instant(), state.lockedUntil()).toSeconds();
        return Math.max(0, seconds + 1);
    }

    public void recordFailure(String username) {
        states.compute(key(username), (k, old) -> {
            Instant now = clock.instant();
            int failures = (old == null || (old.lockedUntil() != null && !old.lockedUntil().isAfter(now)))
                    ? 1 : old.failures() + 1;
            return new State(failures, failures >= maxFailures ? now.plus(lockDuration) : null);
        });
    }

    public void recordSuccess(String username) {
        states.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }
}
