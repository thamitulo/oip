package za.co.oip.companies.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.companies.CompanyDirectorService;
import za.co.oip.companies.api.DirectorDtos.DirectorDto;
import za.co.oip.companies.api.DirectorDtos.DirectorRequest;
import za.co.oip.security.CurrentUser;

import java.util.List;
import java.util.UUID;

/** Directors, members, trustees and shareholders. Personal information: company profile editors only. */
@Tag(name = "Company profile")
@RestController
@RequestMapping("/api/v1/companies/profile/directors")
@PreAuthorize("hasAuthority('COMPANY_PROFILE_EDIT')")
public class CompanyDirectorController {

    private final CompanyDirectorService service;

    public CompanyDirectorController(CompanyDirectorService service) {
        this.service = service;
    }

    @Operation(summary = "List directors / members (identity numbers masked)")
    @GetMapping
    public List<DirectorDto> list() {
        return service.list(CurrentUser.require().tenantId());
    }

    @Operation(summary = "Add a director / member")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DirectorDto create(@Valid @RequestBody DirectorRequest r) {
        return service.create(CurrentUser.require().tenantId(), r);
    }

    @Operation(summary = "Update a director / member")
    @PutMapping("/{id}")
    public DirectorDto update(@PathVariable UUID id, @Valid @RequestBody DirectorRequest r) {
        return service.update(CurrentUser.require().tenantId(), id, r);
    }

    @Operation(summary = "Remove a director / member")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.require().tenantId(), id);
    }
}
