package iselm1.ebpf;

import iselm1.config.LabIds;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** CORS 허용 Origin 은 WebConfig 에서 lab.cors.allowed-origins 로 지정한다. */
@RestController
@RequiredArgsConstructor
public class LogController {

    private final EbpfLogService ebpfLogService;

    @GetMapping(value = "/api/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@RequestParam String labId) {
        if (!LabIds.isValid(labId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "labId 형식이 올바르지 않습니다.");
        }
        SseEmitter emitter = ebpfLogService.subscribe(labId);
        if (emitter == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "동시 접속 수를 초과했습니다.");
        }
        return emitter;
    }
}
