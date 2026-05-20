package com.repoinsight.agent;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.analyzer.model.IntegrationPoint;
import com.repoinsight.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates complete Gherkin feature files for every business flow.
 * Each feature file covers: happy path, boundary values, integration failures (DB, Kafka, S3,
 * Cache, HTTP, third-party), auth failures, and domain-specific edge cases.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GherkinAgent {

    private final LlmClient llmClient;

    private static final String SYSTEM_PROMPT = """
            You are a senior QA engineer and BDD practitioner.
            Generate complete, runnable Gherkin feature files from business flow and integration descriptions.

            Rules:
            1. Feature title = business flow name
            2. Every scenario must have a unique, descriptive title
            3. Use Background for shared Given steps
            4. Cover ALL of the following scenario categories for each flow:
               a) Happy path — nominal, valid inputs, all integrations succeed
               b) Boundary / edge inputs — empty collections, zero amounts, max-length strings,
                  null optionals, duplicate records
               c) DB scenarios — record not found (→ 404), constraint violation (duplicate key),
                  optimistic lock conflict (stale data), transaction rollback on downstream failure
               d) Kafka scenarios — producer timeout / broker unavailable, consumer receives
                  poison pill, DLQ routing after max retries, duplicate message (idempotency)
               e) S3 / Object Store — object not found, upload failure (network), presigned URL
                  expiry, access denied
               f) Cache scenarios — cache miss → DB fallback succeeds, cache miss → DB also fails,
                  stale cache read during concurrent update, explicit cache eviction
               g) HTTP / REST — 400 Bad Request from downstream, 401/403 auth failure,
                  404 not found, 429 rate limit, 500 server error, read timeout, connection refused
               h) Third-party SDK — charge declined (Stripe), SMS delivery failure (Twilio),
                  email bounce, quota exceeded
               i) Auth / authz — unauthenticated request, wrong role, expired JWT, cross-tenant
                  data access attempt
               j) Concurrency — same resource updated concurrently, idempotency key reuse
            4. Use concrete, realistic data (no "foo", "bar", "test123")
            5. Use Scenario Outline + Examples for parameterised edge cases
            6. Tags: @happy-path, @edge-case, @db, @kafka, @s3, @cache, @http, @third-party,
               @auth, @concurrency as appropriate
            7. Return raw Gherkin text only — no JSON wrapper, no markdown fences
            8. Each Feature goes in a separate section delimited by ### FEATURE_FILE: <filename>.feature ###
            """;

    public List<String> generateFeatures(List<BusinessFlow> flows, List<IntegrationPoint> integrations) {
        log.info("GherkinAgent: generating features for {} flows with {} integrations",
                flows.size(), integrations.size());

        List<String> features = new ArrayList<>();

        for (BusinessFlow flow : flows) {
            String context = buildContext(flow, integrations);
            String userPrompt = """
                    Generate a complete Gherkin feature file for the business flow below.
                    Use the integration points to add infrastructure failure scenarios.

                    Business Flow:
                    %s

                    Active Integration Points for this flow:
                    %s

                    Generate ALL scenario categories from the system instructions.
                    """.formatted(formatFlow(flow), formatIntegrations(integrations));

            String featureText = llmClient.runAgent(SYSTEM_PROMPT, userPrompt, context);
            features.add(featureText);
            log.debug("GherkinAgent: generated feature for '{}'", flow.getName());
        }

        return features;
    }

    private String buildContext(BusinessFlow flow, List<IntegrationPoint> integrations) {
        return """
                Flow: %s
                Trigger: %s
                Steps: %s
                Invariants: %s
                Side Effects: %s
                Integration Points: %s
                """.formatted(
                flow.getName(), flow.getTrigger(),
                String.join(", ", flow.getSteps()),
                String.join(", ", flow.getInvariants()),
                String.join(", ", flow.getSideEffects()),
                integrations.stream().map(IntegrationPoint::getName).toList());
    }

    private String formatFlow(BusinessFlow flow) {
        return """
                Name: %s
                Trigger: %s
                Description: %s
                Steps: %s
                Invariants: %s
                Side Effects: %s
                """.formatted(flow.getName(), flow.getTrigger(), flow.getDescription(),
                flow.getSteps(), flow.getInvariants(), flow.getSideEffects());
    }

    private String formatIntegrations(List<IntegrationPoint> points) {
        StringBuilder sb = new StringBuilder();
        for (IntegrationPoint ip : points) {
            sb.append("- [%s] %s (%s) in %s.%s\n"
                    .formatted(ip.getCategory(), ip.getName(), ip.getDirection(),
                            ip.getClassRef(), ip.getMethodRef()));
        }
        return sb.toString();
    }
}
