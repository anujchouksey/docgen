package com.repoinsight.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.repoinsight.analyzer.model.MethodCallNode;
import com.repoinsight.llm.LlmClient;
import com.repoinsight.github.model.GitHubFile;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds method call trees rooted at every entry point (controllers, consumers, schedulers).
 * Each node carries the integration kind so readers know exactly what I/O happens where.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MethodHierarchyAgent {

    private final LlmClient llmClient;

    private static final String SYSTEM_PROMPT = """
            You are a senior Java engineer tracing method call hierarchies.

            For each public entry-point method (REST controller methods, @KafkaListener, @Scheduled, @EventListener):
            build a recursive call tree up to the requested depth.

            Each node must have:
            - className: fully qualified or simple class name
            - methodSignature: method name + parameter types
            - layer: one of CONTROLLER, SERVICE, REPOSITORY, CLIENT, SCHEDULER, CONSUMER
            - integrationKind: one of DB, KAFKA_PRODUCER, KAFKA_CONSUMER, S3, CACHE, HTTP, THIRD_PARTY_SDK, NONE
            - integrationTarget: human description e.g. "PostgreSQL", "Kafka:order.created", "Stripe API"
            - children: nested call nodes
            - depth: integer depth from root (root = 0)

            Return ONLY valid JSON: { "trees": [ { MethodCallNode fields } ] }
            """;

    public List<MethodCallNode> buildTrees(List<GitHubFile> files, int maxDepth) {
        log.info("MethodHierarchyAgent: building call trees (maxDepth={})", maxDepth);

        String context = buildContext(files);
        String userPrompt = """
                Build call hierarchy trees for every entry point in the code above.
                Max depth: %d.
                Include the integrationKind for every leaf that touches external I/O.
                Return the JSON.
                """.formatted(maxDepth);

        TreesWrapper result = llmClient.runAgentWithJsonOutput(SYSTEM_PROMPT, userPrompt, context, TreesWrapper.class);
        log.info("MethodHierarchyAgent: produced {} trees", result.getTrees().size());
        return result.getTrees();
    }

    private String buildContext(List<GitHubFile> files) {
        StringBuilder sb = new StringBuilder("=== SOURCE FILES ===\n\n");
        files.forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                .append(f.getContent()).append("\n\n"));
        return sb.toString();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TreesWrapper {
        private List<MethodCallNode> trees;
    }
}
