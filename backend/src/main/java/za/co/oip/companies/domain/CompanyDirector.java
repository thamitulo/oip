package za.co.oip.companies.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A director, member, trustee or shareholder of the tenant's company (SBD 4 declaration of interest). A company has
 * any number of them. The identity number is personal information and is only ever stored encrypted.
 */
@Getter
@Setter
@Entity
@Table(name = "company_directors")
public class CompanyDirector {

    public enum Role { DIRECTOR, MEMBER, SHAREHOLDER, TRUSTEE }

    public enum IdType { SA_ID, PASSPORT }

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "company_profile_id", nullable = false, updatable = false)
    private UUID companyProfileId;
    @Column(name = "full_name", nullable = false)
    private String fullName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
    @Enumerated(EnumType.STRING)
    @Column(name = "id_type")
    private IdType idType;
    /** AES-GCM ciphertext from {@link za.co.oip.security.FieldEncryptor}; never plaintext. */
    @Column(name = "id_number_enc")
    private String idNumberEncrypted;
    @Column(name = "id_number_last4")
    private String idNumberLast4;
    @Column(name = "income_tax_number")
    private String incomeTaxNumber;
    @Column(name = "shareholding_percentage")
    private BigDecimal shareholdingPercentage;
    @Column(name = "in_service_of_state", nullable = false)
    private boolean inServiceOfState;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
