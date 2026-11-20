package iselm1.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** 회원가입, 이메일 인증번호, 구글 연동 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accounts;
    private final EmailVerificationRepository verifications;
    private final PasswordEncoder passwordEncoder;
    private final VerificationMailer mailer;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    /**
     * 일반 회원으로 가입한다 (이메일 인증 전 상태).
     * googleSub 가 있으면 그 구글 계정을 연동한 채로 만든다.
     */
    @Transactional
    public Account register(String username, String password, String email, String googleSub, String googleEmail) {
        String name = username == null ? "" : username.strip();
        String mail = AuthPolicy.normalizeEmail(email);
        if (!AuthPolicy.isValidUsername(name)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, AuthPolicy.USERNAME_RULE);
        }
        if (!AuthPolicy.isValidPassword(password)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, AuthPolicy.PASSWORD_RULE);
        }
        if (!AuthPolicy.isValidEmail(mail)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, AuthPolicy.EMAIL_RULE);
        }
        if (accounts.existsByUsername(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        if (accounts.existsByEmail(mail)) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
        if (googleSub != null && accounts.findByGoogleSub(googleSub).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "이 구글 계정은 이미 다른 계정에 연동되어 있습니다.");
        }

        Account account = new Account();
        account.setUsername(name);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setEmail(mail);
        account.setEmailVerified(false);
        account.setRole(Account.Role.USER);
        account.setGoogleSub(googleSub);
        account.setGoogleEmail(googleEmail);
        account.setCreatedAt(clock.instant());
        try {
            return accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 아이디·이메일로 가입한 경우
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디 또는 이메일입니다.");
        }
    }

    /**
     * 6자리 인증번호를 새로 만들어 메일로 보낸다. 메일 발송에 실패하면 저장도 되돌린다.
     * @return 다시 보낼 수 있을 때까지의 초
     */
    @Transactional
    public long sendVerificationCode(Long accountId) {
        Account account = get(accountId);
        if (account.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "이미 이메일 인증을 마친 계정입니다.");
        }
        AuthProperties.Verification policy = properties.verification();
        Instant now = clock.instant();
        EmailVerification verification = verifications.findById(accountId).orElse(null);
        if (verification != null) {
            long wait = Duration.between(now, verification.getSentAt().plus(policy.resendCooldown())).toSeconds();
            if (wait > 0) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "인증번호는 " + wait + "초 후에 다시 보낼 수 있습니다.", Map.of("retryAfterSeconds", wait));
            }
        } else {
            verification = new EmailVerification();
            verification.setAccountId(accountId);
        }

        String code = "%06d".formatted(random.nextInt(1_000_000));
        verification.setCodeHash(hash(accountId, code));
        verification.setExpiresAt(now.plus(policy.codeTtl()));
        verification.setSentAt(now);
        verification.setAttempts(0);
        verifications.save(verification);

        mailer.sendCode(account.getEmail(), code, policy.codeTtl().toMinutes());
        return policy.resendCooldown().toSeconds();
    }

    /** 인증번호를 확인한다. 틀린 횟수는 오류가 나도 저장한다. */
    @Transactional(noRollbackFor = ApiException.class)
    public Account verify(Long accountId, String code) {
        Account account = get(accountId);
        if (account.isActive()) {
            return account;
        }
        if (code == null || !code.strip().matches("\\d{6}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "인증번호 숫자 6자리를 입력하세요.");
        }
        EmailVerification verification = verifications.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "인증번호를 먼저 요청하세요."));
        int maxAttempts = properties.verification().maxAttempts();
        if (verification.getExpiresAt().isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 다시 요청하세요.");
        }
        if (verification.getAttempts() >= maxAttempts) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "입력 횟수를 초과했습니다. 인증번호를 다시 요청하세요.");
        }
        byte[] expected = verification.getCodeHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(accountId, code.strip()).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            verification.setAttempts(verification.getAttempts() + 1);
            int remaining = maxAttempts - verification.getAttempts();
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    remaining > 0 ? "인증번호가 올바르지 않습니다. (남은 횟수 " + remaining + "회)"
                            : "입력 횟수를 초과했습니다. 인증번호를 다시 요청하세요.",
                    Map.of("remainingAttempts", remaining));
        }
        account.setEmailVerified(true);
        verifications.delete(verification);
        return account;
    }

    /** 로그인한 계정에 구글 계정을 연동한다. */
    @Transactional
    public Account linkGoogle(Long accountId, String googleSub, String googleEmail) {
        Account account = get(accountId);
        accounts.findByGoogleSub(googleSub)
                .filter(other -> !other.getId().equals(accountId))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "이 구글 계정은 이미 다른 계정에 연동되어 있습니다.");
                });
        account.setGoogleSub(googleSub);
        account.setGoogleEmail(googleEmail);
        return account;
    }

    @Transactional
    public Account unlinkGoogle(Long accountId) {
        Account account = get(accountId);
        account.setGoogleSub(null);
        account.setGoogleEmail(null);
        return account;
    }

    @Transactional
    public void recordLogin(Long accountId) {
        accounts.findById(accountId).ifPresent(account -> account.setLastLoginAt(clock.instant()));
    }

    @Transactional(readOnly = true)
    public Account get(Long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "계정을 찾을 수 없습니다. 다시 로그인하세요."));
    }

    private static String hash(Long accountId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((accountId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
