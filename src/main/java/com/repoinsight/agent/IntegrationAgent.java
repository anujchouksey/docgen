package com.repoinsight.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.repoinsight.analyzer.model.IntegrationPoint;
import com.repoinsight.llm.LlmClient;
import com.repoinsight.github.model.GitHubFile;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Detects every outbound integration: DB, Kafka, S3, Cache, HTTP, third-party SDKs.
 * Results feed the GherkinAgent so it can generate failure-mode scenarios for each integration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrationAgent {

    private final LlmClient llmClient;

    private static final String SYSTEM_PROMPT = """
            You are a platform engineer mapping integration points in a Java codebase.

            Scan for every place the application touches external infrastructure or services.
            Detection patterns:

            DB / JPA:
              @Repository, JdbcTemplate, EntityManager, extends JpaRepository, extends CrudRepository,
              @Query, @Transactional on write methods, NamedParameterJdbcTemplate

            Kafka Producer:
              KafkaTemplate.send(), ProducerRecord, @KafkaHandler on a producer bean

            Kafka Consumer:
              @KafkaListener, ConsumerRecord parameter, AcknowledgingMessageListener

            S3 / Object Store:
              AmazonS3, S3Client (software.amazon.awssdk), S3Template, putObject, getObject, deleteObject

            Cache:
              @Cacheable, @CachePut, @CacheEvict, RedisTemplate, CacheManager.getCache(), Caffeine

            HTTP / REST outbound:
              RestTemplate, WebClient, FeignClient interface, Retrofit @GET/@POST, HttpClient

            Third-party SDK:
              com.stripe, com.twilio, com.sendgrid, com.amazonaws (non-S3), FirebaseApp, etc.

            For each integration point output:
            - category: DB | KAFKA | S3 | CACHE | HTTP | THIRD_PARTY
            - name: descriptive name e.g. "PostgreSQL (JPA)", "Kafka topic: payment.events"
            - classRef: source class
            - methodRef: method where detected
            - detectionPattern: the specific annotation or class that triggered detection
            - direction: READ | WRITE | BOTH | PRODUCE | CONSUME

            Return ONLY valid JSON: { "integrations": [ { IntegrationPoint fields } ] }
            """;

    public List<IntegrationPoint> detect(List<GitHubFile> files) {
        log.info("IntegrationAgent: scanning {} files for integration points", files.size());

        String context = buildContext(files);
        String userPrompt = "Scan all files above. Return every integration point found as JSON.";

        IntegrationsWrapper result = llmClient.runAgentWithJsonOutput(SYSTEM_PROMPT, userPrompt, context,
                IntegrationsWrapper.class);

        log.info("IntegrationAgent: detected {} integration points", result.getIntegrations().size());
        return result.getIntegrations();
    }

    private String buildContext(List<GitHubFile> files) {
        StringBuilder sb = new StringBuilder("=== SOURCE FILES ===\n\n");
        files.forEach(f -> sb.append("--- ").append(f.getPath()).append(" ---\n")
                .append(f.getContent()).append("\n\n"));
        return sb.toString();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class IntegrationsWrapper {
        private List<IntegrationPoint> integrations;
    }
}
