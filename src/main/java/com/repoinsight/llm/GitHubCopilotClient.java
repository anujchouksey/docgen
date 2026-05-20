package com.repoinsight.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LlmClient backed by the GitHub Copilot Chat API (OpenAI-compatible).
 *
 * Auth flow:
 *  1. Exchange a GitHub PAT (with 'copilot' scope) for a short-lived session token
 *     via the Copilot internal token endpoint.
 *  2. Call the chat-completions endpoint with that session token.
 *
 * Enterprise (GHES) support:
 *  Set {@code github.copilot.enterprise-host} (e.g. {@code github.mycompany.com}).
 *  The token-exchange URL will be routed through the GHES API, while
 *  completions still reach {@code api.githubcopilot.com} unless overridden.
 *
 * Environment variables:
 *  GITHUB_TOKEN            — PAT with copilot scope (required)
 *  GITHUB_ENTERPRISE_HOST  — GHES hostname, omit for github.com (optional)
 *  COPILOT_MODEL           — default gpt-4o (optional)
 */
@Component
@ConditionalOnProperty(name = "analysis.engine", havingValue = "COPILOT", matchIfMissing = true)
@Slf4j
public class GitHubCopilotClient implements LlmClient {

    private static final int TOKEN_REFRESH_MARGIN_SECONDS = 60;

    private final WebClient tokenClient;
    private final WebClient completionsClient;
    private final ObjectMapper objectMapper;

    @Value("${github.token:}")
    private String githubPat;

    @Value("${github.copilot.model:gpt-4o}")
    private String model;

    @Value("${github.copilot.max-tokens:4096}")
    private int maxTokens;

    @Value("${github.copilot.timeout-seconds:120}")
    private int timeoutSeconds;

    private final AtomicReference<SessionToken> sessionToken = new AtomicReference<>();
    private final ReentrantLock tokenLock = new ReentrantLock();

    public GitHubCopilotClient(
            @Value("${github.copilot.token-exchange-url:https://api.github.com/copilot_internal/v2/token}") String tokenExchangeUrl,
            @Value("${github.copilot.completions-url:https://api.githubcopilot.com/chat/completions}") String completionsUrl,
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;

        this.tokenClient = WebClient.builder()
                .baseUrl(tokenExchangeUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        this.completionsClient = WebClient.builder()
                .baseUrl(completionsUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Copilot-Integration-Id", "vscode-chat")
                .defaultHeader("Editor-Version", "vscode/1.90.0")
                .defaultHeader("Editor-Plugin-Version", "copilot-chat/0.16.0")
                .build();
    }

    // ── LlmClient impl ────────────────────────────────────────────────────

    @Override
    public String runAgent(String systemPrompt, String userPrompt, String cachedContext) {
        String token = resolveSessionToken();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");

        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        if (cachedContext != null && !cachedContext.isBlank()) {
            ObjectNode ctxMsg = messages.addObject();
            ctxMsg.put("role", "user");
            ctxMsg.put("content", cachedContext);

            ObjectNode ctxAck = messages.addObject();
            ctxAck.put("role", "assistant");
            ctxAck.put("content", "I have reviewed the provided source files and am ready to analyse them.");
        }

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        try {
            String response = completionsClient.post()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText();

            long promptTokens = root.path("usage").path("prompt_tokens").asLong(0);
            long completionTokens = root.path("usage").path("completion_tokens").asLong(0);
            log.debug("Copilot call: {}in / {}out tokens (model={})", promptTokens, completionTokens, model);

            return content;

        } catch (WebClientResponseException e) {
            log.error("Copilot API error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 401) {
                sessionToken.set(null);  // force refresh on next call
            }
            throw new IllegalStateException("Copilot API returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Copilot agent call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T runAgentWithJsonOutput(String systemPrompt, String userPrompt,
                                         String cachedContext, Class<T> responseType) {
        String raw = runAgent(
                systemPrompt + "\n\nIMPORTANT: Respond ONLY with valid JSON. No markdown fences, no explanation.",
                userPrompt,
                cachedContext);
        try {
            String cleaned = raw.strip()
                    .replaceFirst("^```json\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .replaceFirst("^```\\s*", "");
            return objectMapper.readValue(cleaned, responseType);
        } catch (Exception e) {
            log.error("Failed to parse Copilot JSON response: {}", raw, e);
            throw new IllegalStateException("Copilot returned invalid JSON: " + e.getMessage());
        }
    }

    // ── Session token management ──────────────────────────────────────────

    private String resolveSessionToken() {
        SessionToken current = sessionToken.get();
        if (current != null && !current.isExpired()) {
            return current.token();
        }
        tokenLock.lock();
        try {
            // Double-check after lock
            current = sessionToken.get();
            if (current != null && !current.isExpired()) return current.token();

            SessionToken fresh = fetchSessionToken();
            sessionToken.set(fresh);
            return fresh.token();
        } finally {
            tokenLock.unlock();
        }
    }

    private SessionToken fetchSessionToken() {
        if (githubPat == null || githubPat.isBlank()) {
            throw new IllegalStateException(
                    "GitHub token not configured. Set GITHUB_TOKEN env var (PAT with 'copilot' scope) " +
                    "or github.copilot.token in application.yml.");
        }
        try {
            String resp = tokenClient.get()
                    .header(HttpHeaders.AUTHORIZATION, "token " + githubPat)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            JsonNode node = objectMapper.readTree(resp);
            String token = node.path("token").asText();
            long expiresAtEpoch = node.path("expires_at").asLong(
                    System.currentTimeMillis() / 1000 + 1800);

            log.debug("Copilot session token acquired, expires in {}s",
                    expiresAtEpoch - System.currentTimeMillis() / 1000);
            return new SessionToken(token, expiresAtEpoch);

        } catch (WebClientResponseException e) {
            throw new IllegalStateException(
                    "Copilot token exchange failed (" + e.getStatusCode() + "). " +
                    "Ensure your GITHUB_TOKEN has 'copilot' scope and Copilot is enabled for your account/org.",
                    e);
        } catch (Exception e) {
            throw new IllegalStateException("Copilot token exchange error: " + e.getMessage(), e);
        }
    }

    private record SessionToken(String token, long expiresAtEpoch) {
        boolean isExpired() {
            return System.currentTimeMillis() / 1000 >= expiresAtEpoch - TOKEN_REFRESH_MARGIN_SECONDS;
        }
    }
}
