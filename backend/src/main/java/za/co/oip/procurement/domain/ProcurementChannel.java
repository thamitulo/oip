package za.co.oip.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.procurement.domain.ProcurementEnums.ChannelType;
import za.co.oip.procurement.domain.ProcurementEnums.ChannelVerification;

import java.time.Instant;
import java.util.UUID;

/** How an organisation actually procures: public listing, ESD page, supplier platform, login-gated system, etc. */
@Getter
@Setter
@Entity
@Table(name = "procurement_channels")
public class ProcurementChannel {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "organisation_id", nullable = false)
    private UUID organisationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false)
    private ChannelType channelType;
    @Column(nullable = false)
    private String name;
    private String url;
    @Column(name = "supplier_platform_id")
    private UUID supplierPlatformId;
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private ChannelVerification verificationStatus = ChannelVerification.PENDING_VERIFICATION;
    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
