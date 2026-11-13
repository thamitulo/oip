package za.co.oip.identity.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import za.co.oip.tenancy.TenantRole;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 10, max = 128, message = "must be between 10 and 128 characters") String password,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Size(max = 200) String companyName,
            @NotNull @AssertTrue(message = "must be accepted") Boolean acceptTerms,
            Boolean marketingConsent) {

        public boolean wantsMarketing() {
            return Boolean.TRUE.equals(marketingConsent);
        }
    }

    public record LoginRequest(@NotBlank @Email String email,
                               @NotBlank String password,
                               UUID tenantId) {
    }

    /** The refresh token may be sent in the body (API clients) or, from the web app, as the httpOnly cookie. */
    public record RefreshRequest(String refreshToken) {
    }

    public record AuthResponse(String accessToken,
                               Instant accessTokenExpiresAt,
                               String refreshToken,
                               Instant refreshTokenExpiresAt,
                               CurrentUserDto user) {
    }

    public record MembershipDto(UUID tenantId, String tenantName, TenantRole role) {
    }

    public record CurrentUserDto(UUID userId,
                                 String email,
                                 String fullName,
                                 String timezone,
                                 UUID tenantId,
                                 String tenantName,
                                 TenantRole role,
                                 boolean platformAdmin,
                                 Set<String> permissions,
                                 List<MembershipDto> memberships,
                                 boolean emailVerified) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 320) String email) {
    }

    /** Sets a new password from an e-mailed link (password reset or invitation). */
    public record SetPasswordRequest(@NotBlank @Size(max = 200) String token,
                                     @NotBlank @Size(min = 10, max = 128, message = "must be between 10 and 128 characters") String password) {
    }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    /** What an e-mailed link is for, so the page can show the right form. Never reveals the e-mail address. */
    public record TokenInfo(String purpose, String fullName, String tenantName) {
    }
}
