package iselm1.auth;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** 클라이언트에 한국어 메시지로 돌려주는 오류. 응답 본문: {"message": "...", ...extra} */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> extra;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(HttpStatus status, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.extra = extra;
    }
}
