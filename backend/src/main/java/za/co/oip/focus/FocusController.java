package za.co.oip.focus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.common.api.PageResponse;
import za.co.oip.focus.FocusService.FocusArea;
import za.co.oip.focus.FocusService.FocusRequest;
import za.co.oip.focus.FocusService.FocusTemplate;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunitySummaryDto;
import za.co.oip.security.CurrentUser;

import java.util.List;
import java.util.UUID;

/** "My focus": personal saved focus areas such as state land to lease. */
@Tag(name = "My focus")
@Validated
@RestController
@RequestMapping("/api/v1/focus")
@PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
public class FocusController {

    private final FocusService service;

    public FocusController(FocusService service) {
        this.service = service;
    }

    @Operation(summary = "Ready-made focus templates (e.g. state land to lease) with official guidance links")
    @GetMapping("/templates")
    public List<FocusTemplate> templates() {
        return service.templates();
    }

    @Operation(summary = "My focus areas")
    @GetMapping
    public List<FocusArea> list() {
        return service.list(CurrentUser.require());
    }

    @Operation(summary = "Create a focus area")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FocusArea create(@RequestBody FocusRequest body) {
        return service.create(CurrentUser.require(), body);
    }

    @Operation(summary = "Update a focus area")
    @PutMapping("/{id}")
    public FocusArea update(@PathVariable UUID id, @RequestBody FocusRequest body) {
        return service.update(CurrentUser.require(), id, body);
    }

    @Operation(summary = "Delete a focus area")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.require(), id);
    }

    @Operation(summary = "Opportunities matching a focus area, soonest-closing first")
    @GetMapping("/{id}/opportunities")
    public PageResponse<OpportunitySummaryDto> opportunities(@PathVariable UUID id,
                                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.opportunities(CurrentUser.require(), id, page, size);
    }
}
