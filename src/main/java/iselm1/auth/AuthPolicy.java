package iselm1.auth;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 아이디·비밀번호·이메일 규칙. 프론트엔드(src/auth/policy.js)와 같은 규칙을 쓴다.
 */
public final class AuthPolicy {

    private AuthPolicy() {}

    /** 4~20자, 영문 소문자로 시작, 영문 소문자·숫자·_ */
    private static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9_]{3,19}$");

    /** 8~20자, 공백 없는 ASCII. 영문 대문자·소문자·숫자·특수문자를 모두 포함 */
    private static final Pattern PASSWORD_CHARS = Pattern.compile("^[!-~]{8,20}$");
    private static final Pattern HAS_LOWER = Pattern.compile("[a-z]");
    private static final Pattern HAS_UPPER = Pattern.compile("[A-Z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern HAS_SPECIAL = Pattern.compile("[!-/:-@\\[-`{-~]");

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

    public static final String USERNAME_RULE = "아이디는 4~20자이며 영문 소문자로 시작하고 영문 소문자, 숫자, _ 만 쓸 수 있습니다.";
    public static final String PASSWORD_RULE =
            "비밀번호는 8~20자이며 영문 대문자, 소문자, 숫자, 특수문자를 모두 포함해야 합니다 (공백 불가).";
    public static final String EMAIL_RULE = "이메일 형식이 올바르지 않습니다.";

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    public static boolean isValidPassword(String password) {
        return password != null
                && PASSWORD_CHARS.matcher(password).matches()
                && HAS_LOWER.matcher(password).find()
                && HAS_UPPER.matcher(password).find()
                && HAS_DIGIT.matcher(password).find()
                && HAS_SPECIAL.matcher(password).find();
    }

    public static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
