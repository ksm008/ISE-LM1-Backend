package iselm1.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 서버 시작 시 관리자 계정(auth.admin.*)을 만들거나 맞춘다.
 * <ul>
 *   <li>없으면 만든다 (이메일 인증 완료 상태).</li>
 *   <li>있으면 관리자 권한, 이메일 인증, 비밀번호를 설정 값으로 맞춘다.</li>
 * </ul>
 * 설정 값이 규칙에 맞지 않으면 로그만 남기고 건너뛴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final AuthProperties properties;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AuthProperties.Admin admin = properties.admin();
        if (!admin.configured()) {
            log.warn("관리자 계정 설정(auth.admin.username, auth.admin.password)이 없어 관리자 계정을 만들지 않습니다.");
            return;
        }
        String username = admin.username().strip();
        String email = AuthPolicy.normalizeEmail(admin.email());
        if (!AuthPolicy.isValidUsername(username)) {
            log.error("auth.admin.username 이 규칙에 맞지 않아 관리자 계정을 건너뜁니다. {}", AuthPolicy.USERNAME_RULE);
            return;
        }
        if (!AuthPolicy.isValidPassword(admin.password())) {
            log.error("auth.admin.password 가 규칙에 맞지 않아 관리자 계정을 건너뜁니다. {}", AuthPolicy.PASSWORD_RULE);
            return;
        }
        if (!AuthPolicy.isValidEmail(email)) {
            log.error("auth.admin.email 이 비어 있거나 형식이 올바르지 않아 관리자 계정을 건너뜁니다.");
            return;
        }

        Account account = accounts.findByUsername(username).orElse(null);
        if (account == null) {
            if (accounts.existsByEmail(email)) {
                log.error("auth.admin.email({})을 다른 계정이 쓰고 있어 관리자 계정을 만들지 않습니다.", email);
                return;
            }
            account = new Account();
            account.setUsername(username);
            account.setEmail(email);
            account.setCreatedAt(Instant.now());
            account.setPasswordHash(passwordEncoder.encode(admin.password()));
            log.info("관리자 계정 {} 을 만들었습니다.", username);
        } else {
            if (!passwordEncoder.matches(admin.password(), account.getPasswordHash())) {
                account.setPasswordHash(passwordEncoder.encode(admin.password()));
                log.info("관리자 계정 {} 의 비밀번호를 설정 값으로 바꿨습니다.", username);
            }
            if (!email.equals(account.getEmail()) && !accounts.existsByEmail(email)) {
                account.setEmail(email);
            }
        }
        account.setRole(Account.Role.ADMIN);
        account.setEmailVerified(true);
        accounts.save(account);
    }
}
