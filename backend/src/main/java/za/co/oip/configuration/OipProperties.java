package za.co.oip.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Typed application configuration. Secrets are supplied through environment variables
 * (see {@code .env.example}); nothing secret has a usable default.
 */
@Validated
@ConfigurationProperties(prefix = "oip")
public record OipProperties(@Valid @NotNull Security security,
                            @Valid @NotNull Cors cors,
                            @Valid @NotNull Bootstrap bootstrap) {

    public record Security(@NotBlank @Size(min = 32, message = "must be at least 32 characters") String jwtSecret,
                           @NotNull Duration accessTokenTtl,
                           @NotNull Duration refreshTokenTtl,
                           int authRequestsPerMinute) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * Optional first platform administrator, created at startup only if no platform admin exists.
     * Leave the password empty to skip.
     */
    public record Bootstrap(String adminEmail, String adminPassword, String adminTenantName) {
    }
}
