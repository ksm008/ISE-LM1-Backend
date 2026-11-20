package iselm1.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 회원 계정 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** 소문자로 저장한다 */
    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** 연동된 구글 계정 ID (OIDC sub). 연동하지 않았으면 null */
    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Column(name = "google_email", length = 254)
    private String googleEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public enum Role {
        USER,
        /** 모든 콘텐츠에 접근할 수 있다. 이메일 인증이 필요 없다. */
        ADMIN
    }

    /** 사이트를 이용할 수 있는 상태인지 (관리자이거나 이메일 인증을 마침) */
    public boolean isActive() {
        return role == Role.ADMIN || emailVerified;
    }
}
