package za.co.oip.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.AuthenticationFailedException;
import za.co.oip.common.error.Exceptions.ConflictException;
import za.co.oip.configuration.OipProperties;
import za.co.oip.identity.api.AuthDtos.AuthResponse;
import za.co.oip.identity.api.AuthDtos.LoginRequest;
import za.co.oip.identity.api.AuthDtos.RegisterRequest;
import za.co.oip.security.AuthenticatedUser;
import za.co.oip.security.JwtService;
import za.co.oip.tenancy.Tenant;
import za.co.oip.tenancy.TenantRepository;
import za.co.oip.tenancy.TenantService;
import za.co.oip.tenancy.TenantUser;
import za.co.oip.tenancy.TenantUserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registration, login, refresh-token rotation and logout.
 *
 * <p>Refresh tokens are single-use: each refresh revokes the presented token and issues a new one. If a
 * revoked token is presented again, all of the user's sessions are revoked because the token has
 * probably been stolen.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final OipProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;
    private final AccountEmailService accountEmailService;

    public AuthService(UserRepository userRepository, UserSessionRepository sessionRepository,
                       TenantRepository tenantRepository, TenantUserRepository tenantUserRepository,
                       TenantService tenantService, PasswordEncoder passwordEncoder, JwtService jwtService,
                       CurrentUserService currentUserService, AuditService auditService,
                       OipProperties properties, Clock clock, AccountEmailService accountEmailService) {
        this.accountEmailService = accountEmailService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.tenantService = tenantService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
        // Used to keep login timing similar whether or not the email exists.
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, ClientInfo client) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ConflictException("An account with this email address already exists");
        }
        Instant now = clock.instant();
        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setTermsAcceptedAt(now);
        user.setMarketingConsent(request.wantsMarketing());
        user.setMarketingConsentAt(request.wantsMarketing() ? now : null);
        userRepository.save(user);

        Tenant tenant = tenantService.createTenant(request.companyName(), user.getId());
        auditService.recordAs(user.getId(), tenant.getId(), "USER_REGISTERED", "User", user.getId());
        TenantUser membership = tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), user.getId()).orElseThrow();
        accountEmailService.sendVerification(user.getId());
        return issueSession(user, membership, client);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, ClientInfo client) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(request.email().trim());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash()) || !user.isActive()) {
            auditService.recordAs(user.getId(), null, "LOGIN_FAILED", "User", user.getId());
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }
        TenantUser membership = selectMembership(user.getId(), request.tenantId());
        user.setLastLoginAt(clock.instant());
        auditService.recordAs(user.getId(), membership.getTenantId(), "LOGIN_SUCCEEDED", "User", user.getId());
        return issueSession(user, membership, client);
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public AuthResponse refresh(String refreshToken, ClientInfo client) {
        Instant now = clock.instant();
        UserSession session = sessionRepository.findByRefreshTokenHash(TokenHashing.sha256(refreshToken))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));
        if (session.getRevokedAt() != null) {
            log.warn("Revoked refresh token reused for user {}; revoking all sessions", session.getUserId());
            sessionRepository.revokeAllForUser(session.getUserId(), now);
            auditService.recordAs(session.getUserId(), session.getTenantId(), "REFRESH_TOKEN_REUSE_DETECTED",
                    "User", session.getUserId());
            throw new AuthenticationFailedException("Invalid refresh token");
        }
        if (!session.isUsable(now)) {
            throw new AuthenticationFailedException("Refresh token expired");
        }
        User user = userRepository.findById(session.getUserId())
                .filter(User::isActive)
                .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));
        TenantUser membership = selectMembership(user.getId(), session.getTenantId());

        AuthResponse response = issueSession(user, membership, client);
        session.setRevokedAt(now);
        sessionRepository.findByRefreshTokenHash(TokenHashing.sha256(response.refreshToken()))
                .ifPresent(next -> session.setReplacedBy(next.getId()));
        return response;
    }

    @Transactional
    public void logout(String refreshToken) {
        sessionRepository.findByRefreshTokenHash(TokenHashing.sha256(refreshToken)).ifPresent(s -> {
            if (s.getRevokedAt() == null) {
                s.setRevokedAt(clock.instant());
            }
        });
    }

    private TenantUser selectMembership(UUID userId, UUID requestedTenantId) {
        List<TenantUser> memberships = tenantUserRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(userId, TenantUser.Status.ACTIVE).stream()
                .filter(m -> tenantRepository.findById(m.getTenantId())
                        .map(t -> t.getStatus() == Tenant.Status.ACTIVE).orElse(false))
                .toList();
        return memberships.stream()
                .filter(m -> requestedTenantId == null || m.getTenantId().equals(requestedTenantId))
                .findFirst()
                .orElseThrow(() -> new AuthenticationFailedException("No active tenant membership for this account"));
    }

    private AuthResponse issueSession(User user, TenantUser membership, ClientInfo client) {
        Instant now = clock.instant();
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), membership.getTenantId(), user.getEmail(),
                membership.getRole(), user.isPlatformAdmin());
        JwtService.IssuedToken access = jwtService.issueAccessToken(principal);

        String refreshToken = TokenHashing.newOpaqueToken();
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setTenantId(membership.getTenantId());
        session.setRefreshTokenHash(TokenHashing.sha256(refreshToken));
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(properties.security().refreshTokenTtl()));
        session.setUserAgent(truncate(client.userAgent(), 500));
        session.setIpAddress(truncate(client.ipAddress(), 64));
        sessionRepository.save(session);

        return new AuthResponse(access.token(), access.expiresAt(), refreshToken, session.getExpiresAt(),
                currentUserService.describe(principal));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record ClientInfo(String userAgent, String ipAddress) {
    }
}
