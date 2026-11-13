package za.co.oip.identity.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.identity.CurrentUserService;
import za.co.oip.identity.UserManagementService;
import za.co.oip.identity.api.AuthDtos.CurrentUserDto;
import za.co.oip.identity.api.UserDtos.CreateMemberRequest;
import za.co.oip.identity.api.UserDtos.MemberDto;
import za.co.oip.identity.api.UserDtos.UpdateMemberRequest;
import za.co.oip.security.CurrentUser;

import java.util.List;
import java.util.UUID;

@Tag(name = "Users")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final CurrentUserService currentUserService;
    private final UserManagementService userManagementService;

    public UserController(CurrentUserService currentUserService, UserManagementService userManagementService) {
        this.currentUserService = currentUserService;
        this.userManagementService = userManagementService;
    }

    @Operation(summary = "The authenticated user, current tenant, role and permissions")
    @GetMapping("/me")
    public CurrentUserDto me() {
        return currentUserService.describe(CurrentUser.require());
    }

    @Operation(summary = "List members of the current tenant")
    @GetMapping
    public List<MemberDto> list() {
        return userManagementService.listMembers(CurrentUser.require().tenantId());
    }

    @Operation(summary = "Add a member to the current tenant")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public za.co.oip.identity.api.UserDtos.AddMemberResult create(@Valid @RequestBody CreateMemberRequest request) {
        return userManagementService.addMember(CurrentUser.require(), request);
    }

    @Operation(summary = "Send a new invitation link to a member who has not accepted yet")
    @PostMapping("/{id}/resend-invitation")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public za.co.oip.identity.api.UserDtos.AddMemberResult resendInvitation(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return userManagementService.resendInvitation(CurrentUser.require(), id);
    }

    @Operation(summary = "Change a member's role or status in the current tenant")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public MemberDto update(@PathVariable UUID id, @Valid @RequestBody UpdateMemberRequest request) {
        return userManagementService.updateMember(CurrentUser.require(), id, request);
    }
}
