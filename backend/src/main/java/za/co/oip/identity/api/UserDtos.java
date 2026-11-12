package za.co.oip.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import za.co.oip.tenancy.TenantRole;
import za.co.oip.tenancy.TenantUser;

import java.time.Instant;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {
    }

    public record MemberDto(UUID id, String email, String fullName, TenantRole role, TenantUser.Status status,
                            Instant lastLoginAt, Instant memberSince) {
    }

    /**
     * Adds a user to the caller's tenant. If no account exists for the email, one is created with the
     * supplied initial password, which the user should change. (An emailed invitation flow can replace
     * this once outbound email is configured.)
     */
    public record CreateMemberRequest(@NotBlank @Email @Size(max = 320) String email,
                                      @NotBlank @Size(max = 200) String fullName,
                                      @NotNull TenantRole role,
                                      @Size(min = 10, max = 128) String initialPassword) {
    }

    public record UpdateMemberRequest(TenantRole role, TenantUser.Status status, @Size(max = 200) String fullName) {
    }
}
