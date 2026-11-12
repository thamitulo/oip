package za.co.oip.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import za.co.oip.tenancy.TenantRole;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal for a request. Every request is bound to exactly one tenant;
 * all tenant-scoped data access must use {@link #tenantId()} from this principal and never a
 * tenant identifier supplied by the client.
 */
public record AuthenticatedUser(UUID userId, UUID tenantId, String email, TenantRole role, boolean platformAdmin) {

    public List<GrantedAuthority> authorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        role.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        if (platformAdmin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
            authorities.add(new SimpleGrantedAuthority(Permission.SOURCES_MANAGE.name()));
            authorities.add(new SimpleGrantedAuthority(Permission.OPPORTUNITIES_VIEW.name()));
        }
        return authorities;
    }
}
