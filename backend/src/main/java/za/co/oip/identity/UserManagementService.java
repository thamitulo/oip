package za.co.oip.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.ConflictException;
import za.co.oip.common.error.Exceptions.ForbiddenException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.identity.api.UserDtos.AddMemberResult;
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
    private final AccountEmailService accountEmailService;
    private final za.co.oip.tenancy.TenantRepository tenantRepository;

    public UserManagementService(UserRepository userRepository, TenantUserRepository tenantUserRepository,
                                 TenantService tenantService, PasswordEncoder passwordEncoder,
                                 AuditService auditService, AccountEmailService accountEmailService,
                                 za.co.oip.tenancy.TenantRepository tenantRepository) {
        this.accountEmailService = accountEmailService;
        this.tenantRepository = tenantRepository;
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

    /**
     * Adds a member. A person without an account is invited: they receive a link to set their own password
     * (the link is returned to the caller only when e-mail delivery is disabled, so it can be shared another way).
     */
    @Transactional
    public AddMemberResult addMember(AuthenticatedUser actor, CreateMemberRequest request) {
        assertMayGrant(actor, request.role());
        String email = request.email().trim().toLowerCase();
        boolean[] invited = {false};
        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User created = new User();
            created.setEmail(email);
            created.setFullName(request.fullName().trim());
            if (request.initialPassword() != null && !request.initialPassword().isBlank()) {
                created.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
            } else {
                // No usable password until the invitation is accepted.
                created.setPasswordHash(passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()));
                created.setStatus(User.Status.INVITED);
                invited[0] = true;
            }
            return userRepository.saveAndFlush(created); // flushed: the invitation token row references it
        });
        if (tenantUserRepository.findByTenantIdAndUserId(actor.tenantId(), user.getId()).isPresent()) {
            throw new ConflictException("This user is already a member of the business");
        }
        TenantUser membership = tenantService.addMember(actor.tenantId(), user.getId(), request.role());
        auditService.record(actor.tenantId(), "MEMBER_ADDED", "User", user.getId(),
                Map.of("role", request.role(), "invited", invited[0]));
        String url = invited[0] ? accountEmailService.invite(user, actor.tenantId(), tenantName(actor.tenantId()), actor.userId()) : null;
        boolean mail = accountEmailService.mailEnabled();
        return new AddMemberResult(toDto(membership, user), invited[0] && mail, invited[0] && !mail ? url : null);
    }

    /** Sends a fresh invitation to a member who has not accepted yet. */
    @Transactional
    public AddMemberResult resendInvitation(AuthenticatedUser actor, UUID userId) {
        TenantUser membership = tenantUserRepository.findByTenantIdAndUserId(actor.tenantId(), userId)
                .orElseThrow(() -> new NotFoundException("User", userId));
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        if (user.getStatus() != User.Status.INVITED) {
            throw new BusinessRuleException("This person has already accepted their invitation");
        }
        String url = accountEmailService.invite(user, actor.tenantId(), tenantName(actor.tenantId()), actor.userId());
        auditService.record(actor.tenantId(), "INVITATION_RESENT", "User", userId, Map.of());
        boolean mail = accountEmailService.mailEnabled();
        return new AddMemberResult(toDto(membership, user), mail, mail ? null : url);
    }

    private String tenantName(UUID tenantId) {
        return tenantRepository.findById(tenantId).map(za.co.oip.tenancy.Tenant::getName).orElse("your business");
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
                m.getCreatedAt(), u.getStatus() == User.Status.INVITED);
    }
}
