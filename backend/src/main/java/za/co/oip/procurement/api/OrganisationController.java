package za.co.oip.procurement.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.common.api.PageResponse;
import za.co.oip.procurement.OrganisationQueryService;
import za.co.oip.procurement.api.OrganisationDtos.OrganisationDetailDto;
import za.co.oip.procurement.api.OrganisationDtos.OrganisationSummaryDto;
import za.co.oip.procurement.api.OrganisationDtos.SupplierPlatformDto;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;

import java.util.List;
import java.util.UUID;

@Tag(name = "Organisations")
@RestController
@RequestMapping("/api/v1")
public class OrganisationController {

    private final OrganisationQueryService service;

    public OrganisationController(OrganisationQueryService service) {
        this.service = service;
    }

    @Operation(summary = "Search procuring organisations")
    @GetMapping("/organisations")
    public PageResponse<OrganisationSummaryDto> list(@RequestParam(required = false) Sector sector,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        return service.search(sector, q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("name")));
    }

    @Operation(summary = "Organisation detail, including how it procures (channels, supplier platforms, sources)")
    @GetMapping("/organisations/{id}")
    public OrganisationDetailDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Supplier platforms used by procuring organisations")
    @GetMapping("/supplier-platforms")
    public List<SupplierPlatformDto> platforms() {
        return service.platforms();
    }
}
