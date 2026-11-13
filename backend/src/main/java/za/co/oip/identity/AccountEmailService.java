package za.co.oip.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.identity.api.AuthDtos.TokenInfo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Password reset, team invitations and e-mail verification. Links carry a random single-use token; only its
 * SHA-256 hash is stored. Responses never reveal whether an e-mail address has an account.
 */
@Service
public class AccountEmailService {

    public enum Purpose { PASSWORD_RESET, INVITATION, EMAIL_VERIFICATION }

    static final Duration RESET_TTL = Duration.ofHours(1);
    static final Duration INVITATION_TTL = Duration.ofDays(7);
    static final Duration VERIFICATION_TTL = Duration.ofDays(3);
    private static final String INVALID_LINK = "This link is invalid or has expired. Request a new one.";
    private static final Logger log = LoggerFactory.getLogger(AccountEmailService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JavaMailSender mailSender;
    private final Clock clock;
    private final boolean mailEnabled;
    private final String mailFrom;
    private final String appUrl;

    public AccountEmailService(NamedParameterJdbcTemplate jdbc, UserRepository userRepository,
                               UserSessionRepository sessionRepository, PasswordEncoder passwordEncoder,
                               AuditService auditService, JavaMailSender mailSender, Clock clock,
                               @Value("${oip.mail.enabled:false}") boolean mailEnabled,
                               @Value("${oip.mail.from:no-reply@oip.local}") String mailFrom,
                               @Value("${oip.app-url:http://localhost:4200}") String appUrl) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.mailSender = mailSender;
        this.clock = clock;
        this.mailEnabled = mailEnabled;
        this.mailFrom = mailFrom;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    public boolean mailEnabled() {
        return mailEnabled;
    }

    /** Always succeeds from the caller's point of view, so it cannot be used to discover accounts. */
    @Transactional
    public void requestPasswordReset(String email) {
        Optional<User> user = userRepository.findByEmailIgnoreCase(email.trim());
        if (user.isEmpty() || user.get().getStatus() == User.Status.DISABLED) {
            log.info("Password reset requested for an unknown or disabled address");
            return;
        }
        User u = user.get();
        String token = issue(u.getId(), Purpose.PASSWORD_RESET, null, RESET_TTL, null);
        auditService.recordAs(u.getId(), null, "PASSWORD_RESET_REQUESTED", "User", u.getId());
        sendAfterCommit(u.getEmail(), "Reset your Opportunity Intelligence password", """
                Hello %s,

                Someone (hopefully you) asked to reset the password for this account. Use this link within one hour:

                %s/reset-password?token=%s

                If you did not ask for this, ignore this e-mail; your password stays the same.
                """.formatted(u.getFullName(), appUrl, token));
    }

    /** Creates an invitation for a new member and returns the link (e-mailed when delivery is enabled). */
    @Transactional
    public String invite(User user, UUID tenantId, String tenantName, UUID invitedBy) {
        String token = issue(user.getId(), Purpose.INVITATION, tenantId, INVITATION_TTL, invitedBy);
        String url = appUrl + "/accept-invite?token=" + token;
        sendAfterCommit(user.getEmail(), "You have been invited to " + tenantName + " on Opportunity Intelligence", """
                Hello %s,

                You have been added to %s on Opportunity Intelligence, a platform for South African procurement
                opportunities. Set your password with this link (valid for 7 days):

                %s
                """.formatted(user.getFullName(), tenantName, url));
        return url;
    }

    /** Sends (or re-sends) a verification link to the user's own address. */
    @Transactional
    public void sendVerification(UUID userId) {
        User u = userRepository.findById(userId).orElseThrow();
        if (u.getEmailVerifiedAt() != null) {
            return;
        }
        String token = issue(u.getId(), Purpose.EMAIL_VERIFICATION, null, VERIFICATION_TTL, null);
        sendAfterCommit(u.getEmail(), "Confirm your e-mail address", """
                Hello %s,

                Please confirm this e-mail address for your Opportunity Intelligence account:

                %s/verify-email?token=%s
                """.formatted(u.getFullName(), appUrl, token));
    }

    /** Describes a link without using it, so the page can greet the person. */
    @Transactional(readOnly = true)
    public TokenInfo describe(String token) {
        Map<String, Object> row = find(token).orElseThrow(() -> new BusinessRuleException(INVALID_LINK));
        User u = userRepository.findById((UUID) row.get("user_id")).orElseThrow();
        String tenantName = row.get("tenant_id") == null ? null : jdbc.queryForObject(
                "select name from tenants where id = :id", new MapSqlParameterSource("id", row.get("tenant_id")), String.class);
        return new TokenInfo((String) row.get("purpose"), u.getFullName(), tenantName);
    }

    /** Sets a password from a reset or invitation link. Signs the user out everywhere. */
    @Transactional
    public void setPassword(String token, String password) {
        Map<String, Object> row = find(token).filter(r -> !Purpose.EMAIL_VERIFICATION.name().equals(r.get("purpose")))
                .orElseThrow(() -> new BusinessRuleException(INVALID_LINK));
        User u = userRepository.findById((UUID) row.get("user_id")).orElseThrow();
        if (u.getStatus() == User.Status.DISABLED) {
            throw new BusinessRuleException(INVALID_LINK);
        }
        Instant now = clock.instant();
        u.setPasswordHash(passwordEncoder.encode(password));
        if (u.getStatus() == User.Status.INVITED) {
            u.setStatus(User.Status.ACTIVE);
        }
        // Following a link sent to the address proves the person controls it.
        if (u.getEmailVerifiedAt() == null) {
            u.setEmailVerifiedAt(now);
        }
        userRepository.save(u);
        markUsed(row, now);
        // Any other outstanding reset links for this user stop working too.
        jdbc.update("update account_tokens set used_at = :now where user_id = :u and purpose = 'PASSWORD_RESET' and used_at is null",
                new MapSqlParameterSource("u", u.getId()).addValue("now", Timestamp.from(now)));
        sessionRepository.revokeAllForUser(u.getId(), now);
        auditService.recordAs(u.getId(), (UUID) row.get("tenant_id"),
                Purpose.INVITATION.name().equals(row.get("purpose")) ? "INVITATION_ACCEPTED" : "PASSWORD_RESET", "User", u.getId());
    }

    @Transactional
    public void verifyEmail(String token) {
        Map<String, Object> row = find(token).filter(r -> Purpose.EMAIL_VERIFICATION.name().equals(r.get("purpose")))
                .orElseThrow(() -> new BusinessRuleException(INVALID_LINK));
        User u = userRepository.findById((UUID) row.get("user_id")).orElseThrow();
        Instant now = clock.instant();
        if (u.getEmailVerifiedAt() == null) {
            u.setEmailVerifiedAt(now);
            userRepository.save(u);
        }
        markUsed(row, now);
        auditService.recordAs(u.getId(), null, "EMAIL_VERIFIED", "User", u.getId());
    }

    private String issue(UUID userId, Purpose purpose, UUID tenantId, Duration ttl, UUID createdBy) {
        String token = TokenHashing.newOpaqueToken();
        Instant now = clock.instant();
        jdbc.update("""
                insert into account_tokens (id, user_id, purpose, token_hash, tenant_id, expires_at, created_at, created_by)
                values (:id, :u, :p, :h, :t, :exp, :now, :by)""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("u", userId).addValue("p", purpose.name())
                        .addValue("h", TokenHashing.sha256(token)).addValue("t", tenantId)
                        .addValue("exp", Timestamp.from(now.plus(ttl))).addValue("now", Timestamp.from(now)).addValue("by", createdBy));
        return token;
    }

    private Optional<Map<String, Object>> find(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return Optional.empty();
        }
        return jdbc.queryForList("""
                select id, user_id, purpose, tenant_id from account_tokens
                where token_hash = :h and used_at is null and expires_at > :now""",
                new MapSqlParameterSource("h", TokenHashing.sha256(token)).addValue("now", Timestamp.from(clock.instant())))
                .stream().findFirst();
    }

    private void markUsed(Map<String, Object> row, Instant now) {
        jdbc.update("update account_tokens set used_at = :now where id = :id",
                new MapSqlParameterSource("id", row.get("id")).addValue("now", Timestamp.from(now)));
    }

    private void sendAfterCommit(String to, String subject, String body) {
        Runnable send = () -> {
            if (!mailEnabled) {
                log.info("E-mail delivery is disabled; not sending '{}'", subject);
                return;
            }
            try {
                SimpleMailMessage m = new SimpleMailMessage();
                m.setFrom(mailFrom);
                m.setTo(to);
                m.setSubject(subject);
                m.setText(body);
                mailSender.send(m);
            } catch (RuntimeException e) {
                log.warn("Could not send account e-mail '{}': {}", subject, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
