package com.repoinsight.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.llm.LlmClient;
import com.repoinsight.github.model.GitHubFile;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Extracts named business flows from service and domain classes.
 * Produces: what the system does, step-by-step, with invariants and side effects.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessLogicAgent {

    private final LlmClient llmClient;

    private static final String SYSTEM_PROMPT = """
            You are a senior software architect performing codebase analysis.
            Your task: identify and document every distinct business flow in the provided Java source code.

            A business flow is a named, user-visible operation with a clear trigger, sequential steps,
            business rules (invariants), and observable side effects.

            For each flow output:
            - name: concise PascalCase name (e.g. "PlaceOrderFlow")
            - trigger: HTTP method+path, Kafka topic consumed, scheduled cron, or internal event
            - description: 2–4 sentences explaining business purpose and outcome
            - steps: ordered list of what happens (translate code into plain English)
            - invariants: business rules that must hold (validations, preconditions)
            - sideEffects: events published, emails/SMS sent, cache invalidated, external writes

            Return ONLY valid JSON: { "flows": [ { BusinessFlow fields } ] }
            """;

    public List<BusinessFlow> analyse(List<GitHubFile> files) {
        log.info("BusinessLogicAgent: analysing {} files", files.size());

        String context = buildContext(files, List.of("Service", "UseCase", "Handler",
                "Manager", "Facade", "Domain", "Entity", "Aggregate", "Controller", "Resource"));

        String userPrompt = """
                Analyse the Java source code in the context above.
                Focus on service-layer and domain classes.
                Identify every business flow and return the JSON array.
                """;

        FlowsWrapper result = llmClient.runAgentWithJsonOutput(SYSTEM_PROMPT, userPrompt, context, FlowsWrapper.class);

        log.info("BusinessLogicAgent: found {} flows", result.getFlows().size());
        return result.getFlows();
    }

    private String buildContext(List<GitHubFile> files, List<String> classNameHints) {
        StringBuilder sb = new StringBuilder("=== SOURCE FILES ===\n\n");
        files.stream()
                .filter(f -> classNameHints.stream().anyMatch(hint -> f.getPath().contains(hint)))
                .forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                        .append(f.getContent()).append("\n\n"));
        // fallback: include all if filter yields nothing
        if (sb.length() < 100) {
            files.forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                    .append(f.getContent()).append("\n\n"));
        }
        return sb.toString();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FlowsWrapper {
        private List<BusinessFlow> flows;
    }
}
