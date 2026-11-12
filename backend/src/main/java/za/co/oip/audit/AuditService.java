package za.co.oip.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.security.CurrentUser;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Records security- and business-relevant actions. Audit writes join the caller's transaction so
 * that an audit entry exists if and only if the audited change was committed.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID tenantId, String action, String entityType, Object entityId, Map<String, ?> details) {
        AuditEvent event = new AuditEvent();
        event.setTenantId(tenantId);
        event.setActorUserId(CurrentUser.find().map(u -> u.userId()).orElse(null));
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId == null ? null : entityId.toString());
        event.setDetails(details == null || details.isEmpty() ? null : jsonMapper.writeValueAsString(details));
        event.setOccurredAt(clock.instant());
        repository.save(event);
    }

    /** Variant for actions performed by a known actor outside an authenticated request (e.g. registration). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordAs(UUID actorUserId, UUID tenantId, String action, String entityType, Object entityId) {
        AuditEvent event = new AuditEvent();
        event.setTenantId(tenantId);
        event.setActorUserId(actorUserId);
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId == null ? null : entityId.toString());
        event.setOccurredAt(clock.instant());
        repository.save(event);
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> forTenant(UUID tenantId, Pageable pageable) {
        return repository.findByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
    }
}
