package za.co.oip.identity;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.AuthenticationFailedException;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.security.AuthenticatedUser;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POPIA data-subject rights: a user can export the personal information the platform holds about them and delete
 * their account. Deletion anonymises the user record (so audit trails and shared business records stay consistent)
 * and removes personal content: sessions, notifications, preferences and assistant conversations.
 */
@Service
public class PrivacyService {

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public PrivacyService(NamedParameterJdbcTemplate jdbc, UserRepository users, PasswordEncoder passwordEncoder,
                          AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> export(AuthenticatedUser actor) {
        MapSqlParameterSource u = new MapSqlParameterSource("u", actor.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", clock.instant().toString());
        out.put("user", jdbc.queryForMap("""
                select id, email, full_name, timezone, terms_accepted_at, marketing_consent, marketing_consent_at, last_login_at, created_at
                from users where id = :u""", u));
        out.put("memberships", jdbc.queryForList("""
                select t.name tenant, tu.role_code role, tu.status, tu.created_at from tenant_users tu join tenants t on t.id = tu.tenant_id
                where tu.user_id = :u""", u));
        out.put("sessions", jdbc.queryForList("select created_at, expires_at, revoked_at, user_agent, ip_address from user_sessions where user_id = :u", u));
        out.put("notificationPreferences", jdbc.queryForList("select * from notification_preferences where user_id = :u", u));
        out.put("notifications", jdbc.queryForList("select type, title, body, created_at, read_at from notifications where user_id = :u order by created_at desc limit 1000", u));
        out.put("pipelineNotes", jdbc.queryForList("select body, created_at from opportunity_notes where author_id = :u", u));
        out.put("assistantMessages", jdbc.queryForList("""
                select c.title, m.role, m.content, m.created_at from assistant_messages m join assistant_conversations c on c.id = m.conversation_id
                where c.user_id = :u order by m.created_at""", u));
        out.put("auditEvents", jdbc.queryForList("select action, entity_type, occurred_at from audit_events where actor_user_id = :u order by occurred_at desc limit 1000", u));
        return out;
    }

    @Transactional
    public void deleteAccount(AuthenticatedUser actor, String password) {
        User user = users.findById(actor.userId()).orElseThrow();
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthenticationFailedException("Password is incorrect");
        }
        List<UUID> soleOwnerOf = jdbc.queryForList("""
                select tu.tenant_id from tenant_users tu where tu.user_id = :u and tu.role_code = 'TENANT_OWNER' and tu.status = 'ACTIVE'
                  and (select count(*) from tenant_users o where o.tenant_id = tu.tenant_id and o.role_code = 'TENANT_OWNER' and o.status = 'ACTIVE') = 1
                  and (select count(*) from tenant_users m where m.tenant_id = tu.tenant_id and m.status = 'ACTIVE') > 1""",
                new MapSqlParameterSource("u", actor.userId()), UUID.class);
        if (!soleOwnerOf.isEmpty()) {
            throw new BusinessRuleException("You are the only owner of a business that has other members. Make another member an owner first.");
        }
        MapSqlParameterSource u = new MapSqlParameterSource("u", actor.userId()).addValue("now", Timestamp.from(clock.instant()));
        jdbc.update("delete from user_sessions where user_id = :u", u);
        jdbc.update("delete from notifications where user_id = :u", u);
        jdbc.update("delete from notification_preferences where user_id = :u", u);
        jdbc.update("delete from assistant_conversations where user_id = :u", u);
        jdbc.update("update tenant_users set status = 'DISABLED' where user_id = :u", u);
        user.setEmail("deleted-" + user.getId() + "@deleted.invalid");
        user.setFullName("Deleted user");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatus(User.Status.DISABLED);
        user.setMarketingConsent(false);
        user.setMarketingConsentAt(null);
        user.setPlatformAdmin(false);
        users.save(user);
        jdbc.update("update users set deleted_at = :now where id = :u", u);
        audit.recordAs(actor.userId(), actor.tenantId(), "ACCOUNT_DELETED", "User", actor.userId());
    }
}
