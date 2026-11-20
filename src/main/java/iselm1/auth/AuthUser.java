package iselm1.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 세션에 저장하는 로그인 사용자. 비밀번호 해시는 로그인 확인에만 쓰고 세션에는 넣지 않는다.
 * <ul>
 *   <li>ROLE_ADMIN: 관리자 (모든 콘텐츠)</li>
 *   <li>ROLE_USER: 이메일 인증을 마친 회원</li>
 *   <li>ROLE_PENDING: 이메일 인증 전 (인증 관련 API 만 사용 가능)</li>
 * </ul>
 */
public record AuthUser(Long id, String username, String passwordHash, Account.Role role, boolean emailVerified)
        implements UserDetails {

    /** WebSocket 등에서 받은 로그인 정보(Authentication)의 계정 ID. 로그인하지 않았으면 null */
    public static Long idOf(java.security.Principal principal) {
        if (principal instanceof org.springframework.security.core.Authentication auth
                && auth.getPrincipal() instanceof AuthUser user) {
            return user.id();
        }
        return null;
    }

    /** 세션용 (비밀번호 해시 없음) */
    public static AuthUser of(Account account) {
        return new AuthUser(account.getId(), account.getUsername(), null, account.getRole(), account.isEmailVerified());
    }

    /** 로그인 확인용 (비밀번호 해시 포함) */
    static AuthUser withPassword(Account account) {
        return new AuthUser(account.getId(), account.getUsername(), account.getPasswordHash(),
                account.getRole(), account.isEmailVerified());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String role = this.role == Account.Role.ADMIN ? "ROLE_ADMIN" : emailVerified ? "ROLE_USER" : "ROLE_PENDING";
        return List.of(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
