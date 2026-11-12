package za.co.oip.tenancy.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import za.co.oip.tenancy.Tenant;

import java.time.Instant;
import java.util.UUID;

public final class TenantDtos {

    private TenantDtos() {
    }

    public record TenantDto(UUID id, String name, String slug, Tenant.Status status, String timezone,
                            Instant createdAt) {
    }

    public record UpdateTenantRequest(@NotBlank @Size(max = 200) String name,
                                      @NotBlank @Size(max = 64) String timezone) {
    }
}
