package iselm1.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 인증번호 메일 발송. spring.mail.host 를 설정하지 않으면(JavaMailSender 빈이 없으면)
 * 메일을 보내지 않고 인증번호를 서버 로그에만 출력한다 (개발용).
 */
@Slf4j
@Component
public class VerificationMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;

    public VerificationMailer(ObjectProvider<JavaMailSender> mailSender, AuthProperties properties, Environment env) {
        this.mailSender = mailSender;
        String configured = properties.mail().from();
        this.from = configured != null && !configured.isBlank() ? configured : env.getProperty("spring.mail.username");
    }

    public boolean enabled() {
        return mailSender.getIfAvailable() != null;
    }

    public void sendCode(String to, String code, long validMinutes) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("[개발 모드] 메일 설정(spring.mail.host)이 없어 인증번호를 로그로만 출력합니다. to={} code={}", to, code);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(to);
        message.setSubject("[ISE·LM1 스터디] 이메일 인증번호");
        message.setText("""
                ISE·LM1 스터디 이메일 인증번호입니다.

                인증번호: %s

                %d분 안에 인증 화면에 입력해 주세요.
                본인이 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(code, validMinutes));
        try {
            sender.send(message);
        } catch (MailException e) {
            log.error("인증번호 메일 발송 실패 (to={})", to, e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "인증번호 메일을 보내지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }
}
