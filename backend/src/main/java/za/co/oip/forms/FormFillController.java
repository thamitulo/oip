package za.co.oip.forms;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import za.co.oip.forms.FormFillService.Fillable;
import za.co.oip.forms.FormFillService.FormFillDto;
import za.co.oip.pipeline.BidDocumentService;
import za.co.oip.pipeline.BidDocumentService.BidDocumentDto;
import za.co.oip.security.CurrentUser;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Filling tender forms and the bid documents of a pipeline item. Bid team members (PIPELINE_EDIT) only. */
@Tag(name = "Bid documents and form filling")
@RestController
@Validated
@RequestMapping("/api/v1/tenant/opportunities/{opportunityId}")
@PreAuthorize("hasAuthority('PIPELINE_EDIT')")
public class FormFillController {

    private final FormFillService fills;
    private final BidDocumentService bidDocuments;

    public FormFillController(FormFillService fills, BidDocumentService bidDocuments) {
        this.fills = fills;
        this.bidDocuments = bidDocuments;
    }

    public record StartRequest(@NotNull UUID opportunityDocumentId, @Size(max = 500) String zipEntry) {
    }

    @Operation(summary = "Tender documents that can be filled, and earlier form fills")
    @GetMapping("/form-fills")
    public Fillable fillable(@PathVariable UUID opportunityId) {
        return fills.fillable(CurrentUser.require(), opportunityId);
    }

    @Operation(summary = "Start filling a tender document (proposes values from the company profile)")
    @PostMapping("/form-fills")
    @ResponseStatus(HttpStatus.CREATED)
    public FormFillDto start(@PathVariable UUID opportunityId, @Valid @RequestBody StartRequest r) {
        return fills.startFromDocument(CurrentUser.require(), opportunityId, r.opportunityDocumentId(), r.zipEntry());
    }

    @Operation(summary = "Start filling an uploaded PDF form")
    @PostMapping(value = "/form-fills/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FormFillDto upload(@PathVariable UUID opportunityId, @RequestPart("file") MultipartFile file) {
        return fills.startFromUpload(CurrentUser.require(), opportunityId, file);
    }

    @Operation(summary = "A form fill draft")
    @GetMapping("/form-fills/{fillId}")
    public FormFillDto get(@PathVariable UUID opportunityId, @PathVariable UUID fillId) {
        return fills.get(CurrentUser.require(), opportunityId, fillId);
    }

    @Operation(summary = "Page preview (PNG) for reviewing placements")
    @GetMapping(value = "/form-fills/{fillId}/pages/{page}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable UUID opportunityId, @PathVariable UUID fillId, @PathVariable int page) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(fills.preview(CurrentUser.require(), opportunityId, fillId, page));
    }

    @Operation(summary = "Save reviewed placements (edit values, remove, or add your own)")
    @PutMapping("/form-fills/{fillId}")
    public FormFillDto update(@PathVariable UUID opportunityId, @PathVariable UUID fillId,
                              @RequestBody @Size(max = FormFillService.MAX_PLACEMENTS) List<@Valid Placement> placements) {
        return fills.update(CurrentUser.require(), opportunityId, fillId, placements);
    }

    @Operation(summary = "Generate the filled PDF and add it to the bid documents")
    @PostMapping("/form-fills/{fillId}/generate")
    public FormFillDto generate(@PathVariable UUID opportunityId, @PathVariable UUID fillId) {
        return fills.generate(CurrentUser.require(), opportunityId, fillId);
    }

    @Operation(summary = "Delete a form fill draft")
    @DeleteMapping("/form-fills/{fillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFill(@PathVariable UUID opportunityId, @PathVariable UUID fillId) {
        fills.delete(CurrentUser.require(), opportunityId, fillId);
    }

    // ---------------------------------------------------------------- bid documents

    @Operation(summary = "Bid documents of this pipeline item")
    @GetMapping("/bid-documents")
    @PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
    public List<BidDocumentDto> bidDocuments(@PathVariable UUID opportunityId) {
        return bidDocuments.list(CurrentUser.require().tenantId(), opportunityId);
    }

    @Operation(summary = "Upload a bid document (max 25 MB)")
    @PostMapping(value = "/bid-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BidDocumentDto uploadBidDocument(@PathVariable UUID opportunityId, @RequestPart("file") MultipartFile file) {
        var u = CurrentUser.require();
        return bidDocuments.upload(u.tenantId(), u.userId(), opportunityId, file);
    }

    @Operation(summary = "Download a bid document")
    @GetMapping("/bid-documents/{documentId}")
    @PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
    public ResponseEntity<byte[]> downloadBidDocument(@PathVariable UUID opportunityId, @PathVariable UUID documentId) {
        var d = bidDocuments.download(CurrentUser.require().tenantId(), opportunityId, documentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(d.data());
    }

    @Operation(summary = "Delete a bid document")
    @DeleteMapping("/bid-documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBidDocument(@PathVariable UUID opportunityId, @PathVariable UUID documentId) {
        bidDocuments.delete(CurrentUser.require().tenantId(), opportunityId, documentId);
    }
}
