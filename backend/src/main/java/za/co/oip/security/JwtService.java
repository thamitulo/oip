package za.co.oip.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import za.co.oip.configuration.OipProperties;
import za.co.oip.tenancy.TenantRole;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates short-lived HS256 access tokens. Refresh tokens are opaque random values
 * stored hashed in {@code user_sessions} (see {@code AuthService}); they are not JWTs.
 *
 * <p>Isolated behind this class so that a managed identity provider (OIDC) can replace it later.
 */
@Service
public class JwtService {

    private static final String ISSUER = "oip";

    private final SecretKey key;
    private final OipProperties.Security config;
    private final Clock clock;

    public JwtService(OipProperties properties, Clock clock) {
        this.config = properties.security();
        this.key = Keys.hmacShaKeyFor(config.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(AuthenticatedUser user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(config.accessTokenTtl());
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(user.userId().toString())
                .claim("tid", user.tenantId().toString())
                .claim("email", user.email())
                .claim("role", user.role().name())
                .claim("pa", user.platformAdmin())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("tid", String.class)),
                    claims.get("email", String.class),
                    TenantRole.valueOf(claims.get("role", String.class)),
                    Boolean.TRUE.equals(claims.get("pa", Boolean.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
