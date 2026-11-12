package za.co.oip.companies.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.companies.CompanyDocumentService;
import za.co.oip.companies.CompanyDocumentService.CompanyDocumentDto;
import za.co.oip.companies.CompanyProfileService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import za.co.oip.security.CurrentUser;

@Tag(name = "Company profile")
@RestController
@RequestMapping("/api/v1/companies")
public class CompanyProfileController {

    private final CompanyProfileService service;
    private final CompanyDocumentService documentService;

    public CompanyProfileController(CompanyProfileService service, CompanyDocumentService documentService) {
        this.service = service;
        this.documentService = documentService;
    }

    @Operation(summary = "Upload a company document (PDF, Word, Excel, CSV, text; max 25 MB)")
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPANY_PROFILE_EDIT')")
    public CompanyDocumentDto upload(@RequestPart("file") MultipartFile file,
                                     @RequestParam(defaultValue = "OTHER") String documentType,
                                     @RequestParam(required = false) String description) {
        var user = CurrentUser.require();
        return documentService.upload(user.tenantId(), user.userId(), file, documentType, description);
    }

    @Operation(summary = "List the current tenant's company documents")
    @GetMapping("/documents")
    public List<CompanyDocumentDto> documents() {
        return documentService.list(CurrentUser.require().tenantId());
    }

    @Operation(summary = "Download a company document")
    @GetMapping("/documents/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var d = documentService.download(CurrentUser.require().tenantId(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(d.data());
    }

    @Operation(summary = "Delete a company document")
    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('COMPANY_PROFILE_EDIT')")
    public void delete(@PathVariable UUID id) {
        documentService.delete(CurrentUser.require().tenantId(), id);
    }

    @Operation(summary = "Get the current tenant's company profile")
    @GetMapping("/profile")
    public CompanyProfileDto getProfile() {
        return service.get(CurrentUser.require().tenantId());
    }

    @Operation(summary = "List the current tenant's declared capabilities")
    @GetMapping("/capabilities")
    public java.util.List<CompanyProfileDto.CapabilityDto> capabilities() {
        return service.get(CurrentUser.require().tenantId()).capabilities();
    }

    @Operation(summary = "Replace the current tenant's company profile")
    @PutMapping("/profile")
    @PreAuthorize("hasAuthority('COMPANY_PROFILE_EDIT')")
    public CompanyProfileDto updateProfile(@Valid @RequestBody CompanyProfileDto dto) {
        return service.update(CurrentUser.require().tenantId(), dto);
    }
}
