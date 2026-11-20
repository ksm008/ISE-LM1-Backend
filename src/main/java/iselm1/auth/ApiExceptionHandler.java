package iselm1.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.putAll(e.getExtra());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
