package iselm1.auth;

/** 현재 로그인 상태 (GET /api/auth/me 등) */
public record MeResponse(
        boolean authenticated,
        String username,
        String email,
        String role,
        boolean emailVerified,
        /** 사이트를 이용할 수 있는지 (관리자이거나 이메일 인증 완료) */
        boolean active,
        boolean googleLinked,
        String googleEmail
) {

    public static MeResponse anonymous() {
        return new MeResponse(false, null, null, null, false, false, false, null);
    }

    public static MeResponse of(Account account) {
        return new MeResponse(true, account.getUsername(), account.getEmail(), account.getRole().name(),
                account.isEmailVerified(), account.isActive(), account.getGoogleSub() != null, account.getGoogleEmail());
    }
}
