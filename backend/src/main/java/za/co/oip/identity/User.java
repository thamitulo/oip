package za.co.oip.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.common.persistence.BaseEntity;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public enum Status { ACTIVE, DISABLED }

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String timezone = "Africa/Johannesburg";

    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "marketing_consent", nullable = false)
    private boolean marketingConsent;

    @Column(name = "marketing_consent_at")
    private Instant marketingConsentAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
