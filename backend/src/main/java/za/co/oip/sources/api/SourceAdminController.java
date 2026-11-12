package za.co.oip.sources.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.sources.SourceAdminService;
import za.co.oip.sources.api.SourceDtos.SourceDto;
import za.co.oip.sources.api.SourceDtos.SourceRequest;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;

import java.util.UUID;

/** Source registry administration. Access is restricted to platform administrators in SecurityConfig. */
@Tag(name = "Admin: sources")
@RestController
@RequestMapping("/api/v1/admin/sources")
public class SourceAdminController {

    private final SourceAdminService service;

    public SourceAdminController(SourceAdminService service) {
        this.service = service;
    }

    @Operation(summary = "Search the source registry")
    @GetMapping
    public PageResponse<SourceDto> list(@RequestParam(required = false) Sector sector,
                                        @RequestParam(required = false) IntegrationStatus status,
                                        @RequestParam(required = false) Boolean enabled,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200),
                Sort.by("priority").ascending().and(Sort.by("name")));
        return PageResponse.of(service.search(sector, status, enabled, q, pageable), s -> s);
    }

    @Operation(summary = "Get a source")
    @GetMapping("/{id}")
    public SourceDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Register a new source")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceDto create(@Valid @RequestBody SourceRequest request) {
        return service.create(request);
    }

    @Operation(summary = "Update source metadata, enable/disable it, or change its crawl interval")
    @PutMapping("/{id}")
    public SourceDto update(@PathVariable UUID id, @Valid @RequestBody SourceRequest request) {
        return service.update(id, request);
    }
}
