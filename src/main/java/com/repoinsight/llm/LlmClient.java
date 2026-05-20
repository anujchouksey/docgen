package com.repoinsight.llm;

/**
 * Abstraction over any LLM backend (GitHub Copilot, Claude, etc.).
 * All agents depend on this interface — swap the implementation via Spring beans.
 */
public interface LlmClient {

    /**
     * Single agent turn: system prompt + context + user prompt → text response.
     * Implementations should treat {@code cachedContext} as a large, stable block
     * (source files, QA corpus) that the backend can cache where possible.
     */
    String runAgent(String systemPrompt, String userPrompt, String cachedContext);

    /**
     * Same as {@link #runAgent} but deserialises the JSON response into {@code responseType}.
     * Implementations must strip markdown code fences before parsing.
     */
    <T> T runAgentWithJsonOutput(String systemPrompt, String userPrompt,
                                  String cachedContext, Class<T> responseType);
}
