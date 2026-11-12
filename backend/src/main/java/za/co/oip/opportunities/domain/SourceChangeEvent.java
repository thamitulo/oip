package za.co.oip.opportunities.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A change to an opportunity detected at its source, with the previous and new value. */
@Getter
@Setter
@Entity
@Table(name = "source_change_events")
public class SourceChangeEvent {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "opportunity_id", nullable = false)
    private UUID opportunityId;
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;
    @Column(name = "run_id")
    private UUID runId;
    @Column(name = "field_name", nullable = false)
    private String fieldName;
    @Column(name = "old_value")
    private String oldValue;
    @Column(name = "new_value")
    private String newValue;
    /** Material changes (closing date, status, documents, title, briefing) warrant tenant notification. */
    @Column(nullable = false)
    private boolean material;
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
}
