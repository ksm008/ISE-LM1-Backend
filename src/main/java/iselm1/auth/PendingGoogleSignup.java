package iselm1.auth;

import java.io.Serializable;

/**
 * 연동된 계정이 없는 구글 계정으로 로그인했을 때 세션에 잠시 저장하는 구글 정보.
 * 아이디·비밀번호를 입력하면 이 정보로 가입하고, 이 이메일(지메일)로 인증번호를 보낸다.
 */
public record PendingGoogleSignup(String sub, String email, String name) implements Serializable {

    public static final String SESSION_ATTRIBUTE = "iselm1.pendingGoogleSignup";
}
