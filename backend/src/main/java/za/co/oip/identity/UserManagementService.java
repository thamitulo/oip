package za.co.oip.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.ConflictException;
import za.co.oip.common.error.Exceptions.ForbiddenException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.identity.api.UserDtos.CreateMemberRequest;
import za.co.oip.identity.api.UserDtos.MemberDto;
import za.co.oip.identity.api.UserDtos.UpdateMemberRequest;
import za.co.oip.security.AuthenticatedUser;
import za.co.oip.tenancy.TenantRole;
import za.co.oip.tenancy.TenantService;
import za.co.oip.tenancy.TenantUser;
import za.co.oip.tenancy.TenantUserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the members of the caller's tenant. Every operation is scoped to {@code actor.tenantId()};
 * members of other tenants are reported as not found.
 */
@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final TenantUserRepository tenantUserRepository;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserManagementService(UserRepository userRepository, TenantUserRepository tenantUserRepository,
                                 TenantService tenantService, PasswordEncoder passwordEncoder,
                                 AuditService auditService) {
        this.userRepository = userRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.tenantService = tenantService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<MemberDto> listMembers(UUID tenantId) {
        return tenantUserRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(m -> toDto(m, userRepository.findById(m.getUserId()).orElseThrow()))
                .toList();
    }

    @Transactional
    public MemberDto addMember(AuthenticatedUser actor, CreateMemberRequest request) {
        assertMayGrant(actor, request.role());
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            if (request.initialPassword() == null || request.initialPassword().isBlank()) {
                throw new BusinessRuleException("An initial password is required for a new user");
            }
            User created = new User();
            created.setEmail(email);
            created.setFullName(request.fullName().trim());
            created.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
            return userRepository.save(created);
        });
        if (tenantUserRepository.findByTenantIdAndUserId(actor.tenantId(), user.getId()).isPresent()) {
            throw new ConflictException("This user is already a member of the business");
        }
        TenantUser membership = tenantService.addMember(actor.tenantId(), user.getId(), request.role());
        auditService.record(actor.tenantId(), "MEMBER_ADDED", "User", user.getId(), Map.of("role", request.role()));
        return toDto(membership, user);
    }

    @Transactional
    public MemberDto updateMember(AuthenticatedUser actor, UUID userId, UpdateMemberRequest request) {
        TenantUser membership = tenantUserRepository.findByTenantIdAndUserId(actor.tenantId(), userId)
                .orElseThrow(() -> new NotFoundException("User", userId));
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));

        if (membership.getRole().rank() > actor.role().rank()) {
            throw new ForbiddenException("Cannot modify a member with a higher role");
        }
        if (request.role() != null && request.role() != membership.getRole()) {
            assertMayGrant(actor, request.role());
        }
        boolean losesOwnership = membership.getRole() == TenantRole.TENANT_OWNER
                && ((request.role() != null && request.role() != TenantRole.TENANT_OWNER)
                || request.status() == TenantUser.Status.DISABLED);
        if (losesOwnership && tenantUserRepository.countByTenantIdAndRoleAndStatus(actor.tenantId(),
                TenantRole.TENANT_OWNER, TenantUser.Status.ACTIVE) <= 1) {
            throw new BusinessRuleException("A business must keep at least one active owner");
        }
        if (request.role() != null) {
            membership.setRole(request.role());
        }
        if (request.status() != null) {
            membership.setStatus(request.status());
        }
        if (request.fullName() != null && !request.fullName().isBlank() && userId.equals(actor.userId())) {
            user.setFullName(request.fullName().trim());
        }
        tenantUserRepository.saveAndFlush(membership);
        auditService.record(actor.tenantId(), "MEMBER_UPDATED", "User", userId,
                Map.of("role", membership.getRole(), "status", membership.getStatus()));
        return toDto(membership, user);
    }

    private static void assertMayGrant(AuthenticatedUser actor, TenantRole role) {
        if (role.rank() > actor.role().rank()) {
            throw new ForbiddenException("Cannot grant a role higher than your own");
        }
    }

    private static MemberDto toDto(TenantUser m, User u) {
        return new MemberDto(u.getId(), u.getEmail(), u.getFullName(), m.getRole(), m.getStatus(), u.getLastLoginAt(),
                m.getCreatedAt());
    }
}
