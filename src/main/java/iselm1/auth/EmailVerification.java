package iselm1.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 이메일 인증번호. 계정당 하나이며 인증되면 지운다. 인증번호는 해시로만 저장한다. */
@Entity
@Table(name = "email_verifications")
@Getter
@Setter
@NoArgsConstructor
public class EmailVerification {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;
}
