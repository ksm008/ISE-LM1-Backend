package iselm1.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** 세션 로그인 상태를 직접 바꾼다 (아이디 로그인, 구글 로그인, 이메일 인증 후 권한 갱신). */
@Component
public class SessionAuth {

    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    /** 이 계정으로 로그인한다. 세션 고정 공격을 막기 위해 세션 ID 를 바꾼다. */
    public void login(HttpServletRequest request, HttpServletResponse response, Account account) {
        request.getSession(true);
        request.changeSessionId();
        save(request, response, account);
    }

    /** 이메일 인증 등으로 권한이 바뀌었을 때 세션의 사용자 정보를 갱신한다. */
    public void refresh(HttpServletRequest request, HttpServletResponse response, Account account) {
        save(request, response, account);
    }

    /** 로그인 정보를 지운다 (세션은 남는다). */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        SecurityContext empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(empty);
        repository.saveContext(empty, request, response);
    }

    private void save(HttpServletRequest request, HttpServletResponse response, Account account) {
        AuthUser user = AuthUser.of(account);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
    }
}
