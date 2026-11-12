package za.co.oip.pipeline;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.pipeline.PipelineService.DecisionDto;
import za.co.oip.pipeline.PipelineService.NoteDto;
import za.co.oip.pipeline.PipelineService.PipelineDetailDto;
import za.co.oip.pipeline.PipelineService.PipelineItemDto;
import za.co.oip.pipeline.PipelineService.Priority;
import za.co.oip.pipeline.PipelineService.Stage;
import za.co.oip.pipeline.PipelineService.TaskDto;
import za.co.oip.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The current tenant's bid pipeline. Reads need OPPORTUNITIES_VIEW; changes need PIPELINE_EDIT. */
@Tag(name = "Bid pipeline")
@RestController
@RequestMapping("/api/v1/tenant")
@PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
public class PipelineController {

    private final PipelineService service;

    public PipelineController(PipelineService service) {
        this.service = service;
    }

    public record CommentRequest(@Size(max = 2000) String comment) {
    }

    public record StageRequest(@NotNull Stage stage, @Size(max = 2000) String comment) {
    }

    public record AssignRequest(UUID userId) {
    }

    public record DetailsRequest(Priority priority, @PositiveOrZero BigDecimal bidValue, @Size(max = 10000) String outcomeNotes) {
    }

    public record NoteRequest(@NotBlank @Size(max = 10000) String body) {
    }

    public record TaskRequest(@NotBlank @Size(max = 300) String title, Instant dueAt, UUID assigneeId, Instant remindAt) {
    }

    public record TaskStatusRequest(@NotBlank String status) {
    }

    public record DecisionRequest(@NotBlank String decision, @Size(max = 5000) String rationale) {
    }

    @Operation(summary = "The pipeline board")
    @GetMapping("/pipeline")
    public List<PipelineItemDto> board() {
        return service.board(CurrentUser.require().tenantId());
    }

    @Operation(summary = "Pipeline detail for an opportunity: notes, tasks, decisions and history")
    @GetMapping("/opportunities/{id}")
    public PipelineDetailDto detail(@PathVariable UUID id) {
        return service.detail(CurrentUser.require().tenantId(), id);
    }

    @Operation(summary = "Add an opportunity to the pipeline (shortlist)")
    @PostMapping("/opportunities/{id}/shortlist")
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public PipelineItemDto shortlist(@PathVariable UUID id, @Valid @RequestBody(required = false) CommentRequest body) {
        return service.shortlist(CurrentUser.require(), id, body == null ? null : body.comment());
    }

    @Operation(summary = "Change the pipeline stage")
    @PutMapping("/opportunities/{id}/status")
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public PipelineItemDto stage(@PathVariable UUID id, @Valid @RequestBody StageRequest body) {
        return service.changeStage(CurrentUser.require(), id, body.stage(), body.comment());
    }

    @Operation(summary = "Assign to a team member (null to unassign)")
    @PostMapping("/opportunities/{id}/assign")
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public PipelineItemDto assign(@PathVariable UUID id, @RequestBody AssignRequest body) {
        return service.assign(CurrentUser.require(), id, body.userId());
    }

    @Operation(summary = "Update priority, bid value and outcome notes")
    @PutMapping("/opportunities/{id}/details")
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public PipelineItemDto details(@PathVariable UUID id, @Valid @RequestBody DetailsRequest body) {
        return service.updateDetails(CurrentUser.require(), id, body.priority(), body.bidValue(), body.outcomeNotes());
    }

    @Operation(summary = "Add an internal note")
    @PostMapping("/opportunities/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public NoteDto note(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        return service.addNote(CurrentUser.require(), id, body.body());
    }

    @Operation(summary = "Add a task (with optional due date, assignee and reminder)")
    @PostMapping("/opportunities/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public TaskDto task(@PathVariable UUID id, @Valid @RequestBody TaskRequest body) {
        return service.addTask(CurrentUser.require(), id, body.title(), body.dueAt(), body.assigneeId(), body.remindAt());
    }

    @Operation(summary = "Record a go/no-go decision")
    @PostMapping("/opportunities/{id}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public DecisionDto decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return service.decide(CurrentUser.require(), id, body.decision(), body.rationale());
    }

    @Operation(summary = "Open tasks (all, or only mine)")
    @GetMapping("/tasks")
    public List<TaskDto> tasks(@RequestParam(defaultValue = "false") boolean mine) {
        var user = CurrentUser.require();
        return service.openTasks(user.tenantId(), mine ? user.userId() : null);
    }

    @Operation(summary = "Complete, reopen or cancel a task")
    @PutMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('PIPELINE_EDIT')")
    public TaskDto updateTask(@PathVariable UUID taskId, @Valid @RequestBody TaskStatusRequest body) {
        return service.updateTask(CurrentUser.require(), taskId, body.status());
    }
}
