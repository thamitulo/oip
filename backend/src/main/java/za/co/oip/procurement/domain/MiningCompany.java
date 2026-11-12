package za.co.oip.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "mining_companies")
public class MiningCompany {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "organisation_id", nullable = false, unique = true)
    private UUID organisationId;
    @Column(name = "primary_commodities")
    private String primaryCommodities;
    private String notes;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
