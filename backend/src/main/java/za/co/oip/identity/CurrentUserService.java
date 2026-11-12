package za.co.oip.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.identity.api.AuthDtos.CurrentUserDto;
import za.co.oip.identity.api.AuthDtos.MembershipDto;
import za.co.oip.security.AuthenticatedUser;
import za.co.oip.tenancy.Tenant;
import za.co.oip.tenancy.TenantRepository;
import za.co.oip.tenancy.TenantUser;
import za.co.oip.tenancy.TenantUserRepository;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Builds the "who am I" view for an authenticated principal. */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;

    public CurrentUserService(UserRepository userRepository, TenantRepository tenantRepository,
                              TenantUserRepository tenantUserRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
    }

    @Transactional(readOnly = true)
    public CurrentUserDto describe(AuthenticatedUser principal) {
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new NotFoundException("User", principal.userId()));
        Tenant tenant = tenantRepository.findById(principal.tenantId())
                .orElseThrow(() -> new NotFoundException("Tenant", principal.tenantId()));
        List<MembershipDto> memberships = tenantUserRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(user.getId(), TenantUser.Status.ACTIVE).stream()
                .map(m -> new MembershipDto(m.getTenantId(),
                        tenantRepository.findById(m.getTenantId()).map(Tenant::getName).orElse(null), m.getRole()))
                .toList();
        Set<String> permissions = new TreeSet<>();
        principal.authorities().forEach(a -> {
            if (!a.getAuthority().startsWith("ROLE_")) {
                permissions.add(a.getAuthority());
            }
        });
        return new CurrentUserDto(user.getId(), user.getEmail(), user.getFullName(), user.getTimezone(),
                tenant.getId(), tenant.getName(), principal.role(), user.isPlatformAdmin(), permissions, memberships);
    }
}
