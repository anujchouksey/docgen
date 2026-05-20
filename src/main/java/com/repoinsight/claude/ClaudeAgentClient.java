package com.repoinsight.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinsight.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LlmClient backed by Anthropic Claude — secondary option.
 * Activated only when {@code analysis.engine=CLAUDE} is set.
 * Default engine is GitHub Copilot ({@link com.repoinsight.llm.GitHubCopilotClient}).
 */
@Component
@ConditionalOnProperty(name = "analysis.engine", havingValue = "CLAUDE")
@RequiredArgsConstructor
@Slf4j
public class ClaudeAgentClient implements LlmClient {

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.model}")
    private String model;

    @Value("${anthropic.max-tokens}")
    private int maxTokens;

    @Value("${anthropic.prompt-cache-enabled:true}")
    private boolean promptCacheEnabled;

    /**
     * Run a single agent turn: system prompt + user content → text response.
     * Source files are injected as ephemeral cache blocks so repeated calls
     * against the same codebase hit the prompt cache.
     */
    public String runAgent(String systemPrompt, String userPrompt, String cachedContext) {
        // Build the context block — apply ephemeral cache control when enabled so
        // repeated calls against the same large codebase hit the prompt cache.
        TextBlockParam.Builder contextBlockBuilder = TextBlockParam.builder().text(cachedContext);
        if (promptCacheEnabled) {
            contextBlockBuilder.cacheControl(CacheControlEphemeral.builder().build());
        }
        TextBlockParam contextBlock = contextBlockBuilder.build();

        TextBlockParam userBlock = TextBlockParam.builder().text(userPrompt).build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(List.of(
                        com.anthropic.models.messages.MessageParam.builder()
                                .role(com.anthropic.models.messages.MessageParam.Role.USER)
                                .content(List.of(
                                        com.anthropic.models.messages.ContentBlockParam.ofText(contextBlock),
                                        com.anthropic.models.messages.ContentBlockParam.ofText(userBlock)
                                ))
                                .build()
                ))
                .build();

        Message response = anthropicClient.messages().create(params);

        long inputTokens = response.usage().inputTokens();
        long outputTokens = response.usage().outputTokens();
        log.debug("Agent call: {}in / {}out tokens (model={})", inputTokens, outputTokens, model);

        return response.content().stream()
                .filter(block -> block instanceof com.anthropic.models.messages.TextBlock)
                .map(block -> ((com.anthropic.models.messages.TextBlock) block).text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));
    }

    public <T> T runAgentWithJsonOutput(String systemPrompt, String userPrompt,
                                         String cachedContext, Class<T> responseType) {
        String raw = runAgent(
                systemPrompt + "\n\nIMPORTANT: Respond ONLY with valid JSON matching the schema. No markdown fences.",
                userPrompt,
                cachedContext);
        try {
            String cleaned = raw.strip()
                    .replaceFirst("^```json\\s*", "")
                    .replaceFirst("\\s*```$", "");
            return objectMapper.readValue(cleaned, responseType);
        } catch (Exception e) {
            log.error("Failed to parse Claude JSON response: {}", raw, e);
            throw new IllegalStateException("Claude returned invalid JSON: " + e.getMessage());
        }
    }
}
