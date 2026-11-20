package iselm1.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * 구글 로그인(OIDC) 결과 처리. 구글 로그인은 "가입한 계정에 연동"하는 용도다.
 * <ol>
 *   <li>로그인한 사용자가 연동을 시작한 경우: 그 계정에 구글 계정을 연동 → /account</li>
 *   <li>구글 계정이 연동된 계정이 있으면: 그 계정으로 로그인 → /</li>
 *   <li>없으면: 구글 정보를 세션에 두고 아이디/비밀번호만 입력하는 가입 화면(/signup/google)으로.
 *       지메일이 이미 다른 계정의 이메일이면 그 계정으로 로그인해 연동하라고 안내한다.</li>
 * </ol>
 * 어느 경우든 Spring 이 만든 OAuth2 로그인 정보는 우리 계정 로그인(또는 비로그인)으로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final SessionAuth sessionAuth;
    private final AuthProperties properties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User google = (OAuth2User) authentication.getPrincipal();
        String sub = google.getName();
        String email = AuthPolicy.normalizeEmail(google.getAttribute("email"));
        boolean emailVerified = Boolean.TRUE.equals(google.getAttribute("email_verified"));
        String name = google.getAttribute("name");

        HttpSession session = request.getSession();
        Long linkAccountId = (Long) session.getAttribute(AuthController.LINK_ATTRIBUTE);
        session.removeAttribute(AuthController.LINK_ATTRIBUTE);

        // 1. 연동
        if (linkAccountId != null) {
            try {
                Account account = accountService.linkGoogle(linkAccountId, sub, email);
                sessionAuth.login(request, response, account);
                redirect(response, "/account?google=linked");
            } catch (ApiException e) {
                // 원래 계정 로그인은 유지한다
                accounts.findById(linkAccountId).ifPresentOrElse(
                        account -> sessionAuth.login(request, response, account),
                        () -> sessionAuth.clear(request, response));
                redirect(response, "/account?google=in_use");
            }
            return;
        }

        // 2. 연동된 계정으로 로그인
        Optional<Account> linked = accounts.findByGoogleSub(sub);
        if (linked.isPresent()) {
            accountService.recordLogin(linked.get().getId());
            sessionAuth.login(request, response, linked.get());
            redirect(response, linked.get().isActive() ? "/" : "/verify-email");
            return;
        }

        // 3. 연동된 계정이 없음 → 가입 화면
        sessionAuth.clear(request, response);
        if (email == null || !emailVerified) {
            redirect(response, "/login?error=google_email");
            return;
        }
        if (accounts.existsByEmail(email)) {
            redirect(response, "/login?error=google_email_in_use");
            return;
        }
        session.setAttribute(PendingGoogleSignup.SESSION_ATTRIBUTE, new PendingGoogleSignup(sub, email, name));
        redirect(response, "/signup/google");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("구글 로그인 실패: {}", exception.getMessage());
        redirect(response, "/login?error=google_failed");
    }

    private void redirect(HttpServletResponse response, String path) throws IOException {
        response.sendRedirect(properties.frontendUrl() + path);
    }
}
