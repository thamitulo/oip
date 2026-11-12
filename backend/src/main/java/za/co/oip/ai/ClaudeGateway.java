package za.co.oip.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.oip.common.error.Exceptions.BusinessRuleException;

import java.time.Duration;
import java.util.Locale;

/**
 * The single place that talks to Claude. AI features are optional: when no API key is configured
 * ({@code ANTHROPIC_API_KEY}), {@link #isConfigured()} is false and features report themselves unavailable.
 *
 * <p>Defaults follow current Claude API guidance: adaptive thinking (the model default), effort from settings, and
 * the server-side refusal fallback ({@code fallbacks: "default"}) on models that support it, which can be switched
 * off with the {@code ai.server_side_fallback} setting.
 */
@Component
public class ClaudeGateway {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final String apiKey;
    private final String baseUrl;
    private final AiSettingsService settingsService;
    private volatile AnthropicClient client;

    public ClaudeGateway(@Value("${oip.ai.anthropic-api-key:}") String apiKey,
                         @Value("${oip.ai.base-url:}") String baseUrl, AiSettingsService settingsService) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.settingsService = settingsService;
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /** Whether AI is usable right now (key present and master switch on). */
    public boolean isAvailable() {
        return isConfigured() && settingsService.get().enabled();
    }

    public String provider() {
        return "anthropic";
    }

    /** A request builder pre-filled with the configured model, effort and fallback behaviour. */
    public MessageCreateParams.Builder newRequest(long maxTokens) {
        AiSettingsService.AiSettings s = settingsService.get();
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(s.model())
                .maxTokens(maxTokens)
                .outputConfig(OutputConfig.builder().effort(effort(s.effort())).build());
        if (s.serverSideFallback() && supportsServerSideFallback(s.model())) {
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b;
    }

    public Message create(MessageCreateParams params) {
        return client().messages().create(params);
    }

    public <T> StructuredMessage<T> create(StructuredMessageCreateParams<T> params) {
        return client().messages().create(params);
    }

    public String model() {
        return settingsService.get().model();
    }

    private AnthropicClient client() {
        if (!isConfigured()) {
            throw new BusinessRuleException("AI is not configured: set ANTHROPIC_API_KEY on the server");
        }
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey)
                            .maxRetries(2)
                            .timeout(Duration.ofMinutes(5));
                    if (!baseUrl.isEmpty()) {
                        b.baseUrl(baseUrl);
                    }
                    client = b.build();
                }
                c = client;
            }
        }
        return c;
    }

    static boolean supportsServerSideFallback(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.startsWith("claude-opus-5") || m.startsWith("claude-fable-5") || m.equals("claude-sonnet-5-5");
    }

    static OutputConfig.Effort effort(String e) {
        return switch (e) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }
}
