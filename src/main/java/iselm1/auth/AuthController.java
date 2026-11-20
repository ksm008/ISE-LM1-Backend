package iselm1.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 회원 API (/api/auth/**). 로그아웃은 Spring Security 가 처리한다 (POST /api/auth/logout).
 * 상태를 바꾸는 요청(POST)은 CSRF 토큰이 필요하다: 쿠키 XSRF-TOKEN 값을 헤더 X-XSRF-TOKEN 으로 보낸다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 로그인한 사용자가 구글 연동을 시작했을 때 세션에 남기는 계정 ID */
    static final String LINK_ATTRIBUTE = "iselm1.linkGoogleAccountId";

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final SessionAuth sessionAuth;
    private final LoginAttemptLimiter loginLimiter;
    private final VerificationMailer mailer;
    private final AuthProperties properties;
    private final ObjectProvider<ClientRegistrationRepository> googleClients;

    /** 없는 아이디로 로그인할 때도 비밀번호 비교 시간을 들여 아이디 존재 여부가 드러나지 않게 한다 */
    private String dummyHash;

    public record LoginRequest(String username, String password) {}

    public record SignupRequest(String username, String password, String email) {}

    public record GoogleSignupRequest(String username, String password) {}

    public record VerifyRequest(String code) {}

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthUser user) {
        if (user == null) {
            return MeResponse.anonymous();
        }
        return accounts.findById(user.id()).map(MeResponse::of).orElse(MeResponse.anonymous());
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("googleEnabled", googleClients.getIfAvailable() != null, "mailEnabled", mailer.enabled());
    }

    /** CSRF 토큰을 발급한다 (쿠키 XSRF-TOKEN 도 함께 설정된다). */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public MeResponse login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String username = body.username() == null ? "" : body.username().strip();
        String password = body.password() == null ? "" : body.password();
        long locked = loginLimiter.lockedSeconds(username);
        if (locked > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "로그인 시도가 너무 많습니다. " + locked + "초 후에 다시 시도하세요.", Map.of("retryAfterSeconds", locked));
        }

        Optional<Account> account = accounts.findByUsername(username);
        boolean matches;
        if (account.isPresent()) {
            matches = passwordEncoder.matches(password, account.get().getPasswordHash());
        } else {
            passwordEncoder.matches(password, dummyHash()); // 시간만 맞춘다
            matches = false;
        }
        if (!matches) {
            loginLimiter.recordFailure(username);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        loginLimiter.recordSuccess(username);
        accountService.recordLogin(account.get().getId());
        sessionAuth.login(request, response, account.get());
        return MeResponse.of(account.get());
    }

    /** 아이디/비밀번호 회원가입. 가입하면 로그인되고(인증 전 상태) 이메일로 인증번호를 보낸다. */
    @PostMapping("/signup")
    public Map<String, Object> signup(@RequestBody SignupRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        Account account = accountService.register(body.username(), body.password(), body.email(), null, null);
        sessionAuth.login(request, response, account);
        return withMailResult(account);
    }

    /** 연동된 계정이 없는 구글 계정으로 로그인한 뒤, 아이디/비밀번호만 입력해 가입한다. 지메일로 인증번호를 보낸다. */
    @PostMapping("/signup/google")
    public Map<String, Object> signupWithGoogle(@RequestBody GoogleSignupRequest body, HttpServletRequest request,
                                                HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        PendingGoogleSignup pending = session == null ? null
                : (PendingGoogleSignup) session.getAttribute(PendingGoogleSignup.SESSION_ATTRIBUTE);
        if (pending == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "구글 로그인 정보가 없거나 만료되었습니다. 다시 구글로 로그인하세요.");
        }
        Account account = accountService.register(body.username(), body.password(), pending.email(),
                pending.sub(), pending.email());
        session.removeAttribute(PendingGoogleSignup.SESSION_ATTRIBUTE);
        sessionAuth.login(request, response, account);
        return withMailResult(account);
    }

    /** 구글 가입 화면에 보여 줄 구글 계정 정보 */
    @GetMapping("/google/pending")
    public Map<String, String> pendingGoogle(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        PendingGoogleSignup pending = session == null ? null
                : (PendingGoogleSignup) session.getAttribute(PendingGoogleSignup.SESSION_ATTRIBUTE);
        if (pending == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "구글 로그인 정보가 없거나 만료되었습니다. 다시 구글로 로그인하세요.");
        }
        return Map.of("email", pending.email(), "name", pending.name() == null ? "" : pending.name());
    }

    @PostMapping("/email/send")
    public Map<String, Object> sendCode(@AuthenticationPrincipal AuthUser user) {
        long cooldown = accountService.sendVerificationCode(user.id());
        return Map.of("cooldownSeconds", cooldown, "mailEnabled", mailer.enabled());
    }

    @PostMapping("/email/verify")
    public MeResponse verify(@AuthenticationPrincipal AuthUser user, @RequestBody VerifyRequest body,
                             HttpServletRequest request, HttpServletResponse response) {
        Account account = accountService.verify(user.id(), body.code());
        sessionAuth.refresh(request, response, account);
        return MeResponse.of(account);
    }

    /** 로그인한 계정에 구글 계정을 연동한다 (브라우저가 이 주소로 이동). 구글 로그인 후 /account 로 돌아간다. */
    @GetMapping("/google/link")
    public void linkGoogle(@AuthenticationPrincipal AuthUser user, HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        if (googleClients.getIfAvailable() == null) {
            response.sendRedirect(properties.frontendUrl() + "/account?google=disabled");
            return;
        }
        request.getSession(true).setAttribute(LINK_ATTRIBUTE, user.id());
        response.sendRedirect(request.getContextPath() + "/oauth2/authorization/google");
    }

    @PostMapping("/google/unlink")
    public MeResponse unlinkGoogle(@AuthenticationPrincipal AuthUser user) {
        return MeResponse.of(accountService.unlinkGoogle(user.id()));
    }

    /** 가입 직후 인증번호를 보낸다. 메일 발송에 실패해도 가입은 유지하고, 인증 화면에서 다시 보낼 수 있다. */
    private Map<String, Object> withMailResult(Account account) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("me", MeResponse.of(account));
        try {
            result.put("cooldownSeconds", accountService.sendVerificationCode(account.getId()));
            result.put("mailSent", true);
        } catch (ApiException e) {
            result.put("mailSent", false);
            result.put("mailError", e.getMessage());
        }
        result.put("mailEnabled", mailer.enabled());
        return result;
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode("dummy-password-for-timing");
        }
        return dummyHash;
    }
}
