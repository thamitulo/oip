package za.co.oip.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A mine, plant or site run by a mining company; tenders often name the operation rather than the company. */
@Getter
@Setter
@Entity
@Table(name = "mining_operations")
public class MiningOperation {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "mining_company_id", nullable = false)
    private UUID miningCompanyId;
    @Column(nullable = false)
    private String name;
    private String province;
    private String locality;
    private String commodity;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
