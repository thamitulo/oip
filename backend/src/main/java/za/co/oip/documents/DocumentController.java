package za.co.oip.documents;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.documents.DocumentEntities.SourceDocument;
import za.co.oip.opportunities.domain.OpportunityDocument;
import za.co.oip.opportunities.domain.OpportunityDocumentRepository;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Documents")
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
public class DocumentController {

    private final DocumentProcessingService processingService;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final OpportunityRequirementRepository requirementRepository;
    private final OpportunityDocumentRepository documentRepository;
    private final DocumentStorage storage;
    private final TaskExecutor executor;

    public DocumentController(DocumentProcessingService processingService, SourceDocumentRepository sourceDocumentRepository,
                              OpportunityRequirementRepository requirementRepository,
                              OpportunityDocumentRepository documentRepository, DocumentStorage storage,
                              @Qualifier("crawlExecutor") TaskExecutor executor) {
        this.processingService = processingService;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.requirementRepository = requirementRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.executor = executor;
    }

    public record RequirementDto(UUID id, String type, String value, Boolean mandatory, Provenance provenance,
                                 String evidence, String documentTitle) {
    }

    @Operation(summary = "Requirements identified for an opportunity, each with its provenance and evidence")
    @GetMapping("/opportunities/{id}/requirements")
    @Transactional(readOnly = true)
    public List<RequirementDto> requirements(@PathVariable UUID id) {
        Map<UUID, SourceDocument> docs = sourceDocumentRepository.findByOpportunityId(id).stream()
                .collect(Collectors.toMap(SourceDocument::getId, Function.identity()));
        Map<UUID, String> titles = documentRepository.findByOpportunityIdOrderByFirstSeenAtAsc(id).stream()
                .collect(Collectors.toMap(OpportunityDocument::getId, d -> d.getTitle() == null ? d.getUrl() : d.getTitle()));
        // The same requirement found in several documents is listed once.
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<RequirementDto> list = new java.util.ArrayList<>(requirementRepository.findByOpportunityIdOrderByRequirementTypeAsc(id).stream()
                .filter(r -> seen.add(r.getRequirementType() + "|" + r.getValue() + "|" + r.getProvenance()))
                .map(r -> new RequirementDto(r.getId(), r.getRequirementType(), r.getValue(), r.getMandatory(), r.getProvenance(),
                        r.getEvidence(), r.getSourceDocumentId() == null || !docs.containsKey(r.getSourceDocumentId()) ? null
                        : titles.get(docs.get(r.getSourceDocumentId()).getOpportunityDocumentId())))
                .toList());
        return mergePreferenceSystems(list);
    }

    private static final java.util.regex.Pattern PREFERENCE_SYSTEM = java.util.regex.Pattern.compile("\\b(80/20|90/10)\\b");

    /**
     * When the documents mention different preference point systems (bid forms list both as alternatives), one
     * conditional entry is shown instead of contradictory ones.
     */
    static List<RequirementDto> mergePreferenceSystems(List<RequirementDto> list) {
        List<RequirementDto> prefs = list.stream().filter(r -> "PREFERENCE_POINT_SYSTEM".equals(r.type())).toList();
        java.util.Set<String> systems = new java.util.TreeSet<>();
        prefs.forEach(r -> PREFERENCE_SYSTEM.matcher(r.value() == null ? "" : r.value()).results().forEach(m -> systems.add(m.group(1))));
        if (systems.size() < 2) {
            return list;
        }
        RequirementDto first = prefs.getFirst();
        RequirementDto merged = new RequirementDto(first.id(), first.type(),
                String.join(" or ", systems) + " (the documents list both; check which applies)", null,
                first.provenance(), first.evidence(), first.documentTitle());
        List<RequirementDto> out = new java.util.ArrayList<>();
        for (RequirementDto r : list) {
            if (r == first) out.add(merged);
            else if (!prefs.contains(r)) out.add(r);
        }
        return out;
    }

    @Operation(summary = "Download and extract this opportunity's documents now (asynchronous)")
    @PostMapping("/opportunities/{id}/documents/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void process(@PathVariable UUID id) {
        if (documentRepository.findByOpportunityIdOrderByFirstSeenAtAsc(id).isEmpty()) {
            throw new NotFoundException("Documents for opportunity", id);
        }
        executor.execute(() -> processingService.processOpportunity(id));
    }

    @Operation(summary = "The stored original of a document (kept while the opportunity is relevant)")
    @GetMapping("/documents/{id}/original")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> original(@PathVariable UUID id) {
        SourceDocument sd = sourceDocumentRepository.findById(id).orElseThrow(() -> new NotFoundException("Document", id));
        if (sd.getStorageKey() == null) {
            throw new NotFoundException("Stored original of document", id);
        }
        OpportunityDocument doc = documentRepository.findById(sd.getOpportunityDocumentId()).orElseThrow();
        String name = doc.getTitle() == null ? "document" : doc.getTitle();
        return ResponseEntity.ok()
                .contentType(sd.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(sd.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(storage.get(sd.getStorageKey()));
    }

    @Operation(summary = "Text extracted from a document")
    @GetMapping(value = "/documents/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE)
    @Transactional(readOnly = true)
    public String text(@PathVariable UUID id) {
        SourceDocument sd = sourceDocumentRepository.findById(id).orElseThrow(() -> new NotFoundException("Document", id));
        String text = sd.getTextContent() == null ? "" : sd.getTextContent();
        return text.length() > 300_000 ? text.substring(0, 300_000) + "\n…[truncated]" : text;
    }
}
