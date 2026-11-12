package za.co.oip.tenancy.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.security.CurrentUser;
import za.co.oip.tenancy.TenantService;
import za.co.oip.tenancy.api.TenantDtos.TenantDto;
import za.co.oip.tenancy.api.TenantDtos.UpdateTenantRequest;

@Tag(name = "Tenants")
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(summary = "Get the tenant the caller is currently signed in to")
    @GetMapping("/current")
    public TenantDto current() {
        return tenantService.getDto(CurrentUser.require().tenantId());
    }

    @Operation(summary = "Update the current tenant")
    @PutMapping("/current")
    @PreAuthorize("hasAuthority('TENANT_MANAGE')")
    public TenantDto update(@Valid @RequestBody UpdateTenantRequest request) {
        return tenantService.update(CurrentUser.require().tenantId(), request);
    }
}
