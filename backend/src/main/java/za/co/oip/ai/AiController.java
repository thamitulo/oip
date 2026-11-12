package za.co.oip.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import za.co.oip.ai.AiSettingsService.AiSettings;
import za.co.oip.ai.AssistantService.ConversationDto;
import za.co.oip.ai.AssistantService.MessageDto;
import za.co.oip.ai.AssistantService.ReplyDto;
import za.co.oip.identity.CurrentUserService;
import za.co.oip.security.CurrentUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AI")
@RestController
public class AiController {

    private final AssistantService assistant;
    private final AiAnalysisService analysis;
    private final AiSettingsService settings;
    private final ClaudeGateway gateway;
    private final AiAuditService audit;
    private final CurrentUserService currentUserService;

    public AiController(AssistantService assistant, AiAnalysisService analysis, AiSettingsService settings, ClaudeGateway gateway,
                        AiAuditService audit, CurrentUserService currentUserService) {
        this.assistant = assistant;
        this.analysis = analysis;
        this.settings = settings;
        this.gateway = gateway;
        this.audit = audit;
        this.currentUserService = currentUserService;
    }

    public record AiStatus(boolean configured, boolean enabled, boolean assistantAvailable, boolean matchExplanationAvailable,
                           boolean classificationAvailable, String model) {
    }

    public record AskRequest(UUID conversationId, @NotBlank @Size(max = 4000) String message) {
    }

    @Operation(summary = "Which AI features are available")
    @GetMapping("/api/v1/ai/status")
    public AiStatus status() {
        AiSettings s = settings.get();
        boolean on = gateway.isConfigured() && s.enabled();
        return new AiStatus(gateway.isConfigured(), s.enabled(), on && s.assistantEnabled(), on && s.matchExplanationEnabled(),
                on && s.classificationEnabled(), s.model());
    }

    @Operation(summary = "Ask the assistant (starts a conversation when conversationId is empty)")
    @PostMapping("/api/v1/ai/assistant/messages")
    @PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
    public ReplyDto ask(@Valid @RequestBody AskRequest body) {
        var user = CurrentUser.require();
        var me = currentUserService.describe(user);
        String context = "User: " + me.fullName() + " (" + me.role() + ") at " + me.tenantName() + ".";
        return assistant.ask(user, body.conversationId(), body.message().trim(), context);
    }

    @Operation(summary = "My assistant conversations")
    @GetMapping("/api/v1/ai/assistant/conversations")
    public List<ConversationDto> conversations() {
        return assistant.conversations(CurrentUser.require());
    }

    @Operation(summary = "Messages in a conversation")
    @GetMapping("/api/v1/ai/assistant/conversations/{id}")
    public List<MessageDto> messages(@PathVariable UUID id) {
        return assistant.messages(CurrentUser.require(), id);
    }

    @Operation(summary = "Delete a conversation")
    @DeleteMapping("/api/v1/ai/assistant/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        assistant.delete(CurrentUser.require(), id);
    }

    @Operation(summary = "AI assessment of how well an opportunity fits your company (stored with your match)")
    @PostMapping("/api/v1/opportunities/{id}/match/explain")
    @PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
    public Map<String, Object> explain(@PathVariable UUID id) {
        return analysis.explainMatch(CurrentUser.require(), id);
    }

    @Operation(summary = "AI classification of an opportunity (industries, services, requirements; stored with provenance AI)")
    @PostMapping("/api/v1/opportunities/{id}/classify")
    @PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
    public Map<String, Object> classify(@PathVariable UUID id) {
        var user = CurrentUser.require();
        return analysis.classify(user.tenantId(), user.userId(), id);
    }

    @Operation(summary = "AI settings (platform administrators)")
    @GetMapping("/api/v1/admin/ai/settings")
    public Map<String, Object> adminSettings() {
        return Map.of("settings", settings.get(), "providerConfigured", gateway.isConfigured(), "provider", gateway.provider());
    }

    @Operation(summary = "Update AI settings (platform administrators)")
    @PutMapping("/api/v1/admin/ai/settings")
    public AiSettings updateSettings(@RequestBody AiSettings body) {
        return settings.update(body);
    }

    @Operation(summary = "AI usage for the last 30 days (platform administrators)")
    @GetMapping("/api/v1/admin/ai/usage")
    public List<Map<String, Object>> usage() {
        return audit.usage();
    }
}
