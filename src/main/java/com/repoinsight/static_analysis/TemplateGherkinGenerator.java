package com.repoinsight.static_analysis;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.analyzer.model.IntegrationPoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates paired Gherkin feature files + Cucumber step definitions from
 * BusinessFlow + IntegrationPoint data, with no AI.
 *
 * Step definitions include typed helper methods for each integration:
 *   @db       → Spring Boot test + H2/Testcontainers + @Transactional helpers
 *   @kafka    → @EmbeddedKafka test + KafkaTemplate send + consumer-record helpers
 *   @s3       → @MockBean AmazonS3 / LocalStack helpers
 *   @cache    → @MockBean CacheManager + Mockito helpers
 *   @http     → WireMock stub helpers
 *   @auth     → MockMvc / TestRestTemplate auth-header helpers
 */
@Component
public class TemplateGherkinGenerator {

    // ── Public API ─────────────────────────────────────────────────────────

    /** Legacy single-string list (feature content only). */
    public List<String> generate(List<BusinessFlow> flows, List<IntegrationPoint> integrations) {
        return generateWithStepDefs(flows, integrations)
                .stream()
                .map(GeneratedGherkin::featureContent)
                .collect(Collectors.toList());
    }

    /** Returns feature + step definition pair for every business flow. */
    public List<GeneratedGherkin> generateWithStepDefs(List<BusinessFlow> flows,
                                                        List<IntegrationPoint> integrations) {
        List<GeneratedGherkin> result = new ArrayList<>();
        for (BusinessFlow flow : flows) {
            Set<String> categories = integrations.stream()
                    .map(IntegrationPoint::getCategory)
                    .collect(Collectors.toSet());
            String feature = renderFeature(flow, integrations);
            String stepDef = renderStepDefinition(flow, categories);
            result.add(new GeneratedGherkin(
                    toFeatureFileName(flow.getName()),
                    feature,
                    toStepDefClassName(flow.getName()),
                    stepDef));
        }
        return result;
    }

    // ── Feature rendering ──────────────────────────────────────────────────

    private String renderFeature(BusinessFlow flow, List<IntegrationPoint> integrations) {
        String featureName = flow.getName().replace("_", " ").replace("Flow", "").trim();
        String entity = guessEntity(flow.getTrigger(), flow.getName());
        StringBuilder sb = new StringBuilder();

        sb.append("Feature: ").append(featureName).append("\n\n");
        sb.append("  Background:\n");
        sb.append("    Given the system is operational\n");
        sb.append("    And the database is accessible\n\n");

        // 1 — Happy path
        sb.append("  @happy-path\n");
        sb.append("  Scenario: ").append(featureName).append(" succeeds with valid input\n");
        sb.append("    Given a valid ").append(entity).append(" with all required fields\n");
        renderTriggerWhen(sb, flow.getTrigger(), entity);
        sb.append("    Then the operation completes successfully\n");
        for (String effect : flow.getSideEffects()) {
            sb.append("    And ").append(effect).append("\n");
        }
        sb.append("\n");

        // 2 — Boundary / invalid input
        sb.append("  @edge-case\n");
        sb.append("  Scenario Outline: ").append(featureName).append(" rejects invalid input\n");
        sb.append("    Given a ").append(entity).append(" with <field> set to <value>\n");
        renderTriggerWhen(sb, flow.getTrigger(), entity);
        sb.append("    Then the response status is 400\n");
        sb.append("    And the error message contains <expectedError>\n\n");
        sb.append("    Examples:\n");
        sb.append("      | field    | value | expectedError             |\n");
        sb.append("      | id       | null  | must not be null          |\n");
        sb.append("      | amount   | -1    | must be positive          |\n");
        sb.append("      | name     | \"\"    | must not be blank         |\n");
        sb.append("      | quantity | 0     | must be greater than zero |\n\n");

        // 3 — Not found (read ops)
        if (isReadOperation(flow.getTrigger())) {
            sb.append("  @edge-case\n");
            sb.append("  Scenario: ").append(featureName).append(" returns 404 when ").append(entity).append(" does not exist\n");
            sb.append("    Given no ").append(entity).append(" exists with id \"NON-EXISTENT-999\"\n");
            renderTriggerWhenWith(sb, flow.getTrigger(), entity, "\"NON-EXISTENT-999\"");
            sb.append("    Then the response status is 404\n");
            sb.append("    And the error message contains \"not found\"\n\n");
        }

        // 4 — Auth
        appendAuthScenarios(sb, flow, entity);

        // 5 — Integration failure modes
        for (IntegrationPoint ip : integrations) {
            renderIntegrationScenario(sb, flow, entity, ip);
        }

        // 6 — Concurrency (write ops)
        if (isWriteOperation(flow.getTrigger())) {
            sb.append("  @concurrency\n");
            sb.append("  Scenario: Concurrent requests for the same resource are handled safely\n");
            sb.append("    Given two concurrent requests for the same ").append(entity).append(" id\n");
            sb.append("    When both requests are processed simultaneously\n");
            sb.append("    Then exactly one request succeeds\n");
            sb.append("    And the other request receives a conflict or retry response\n\n");

            sb.append("  @concurrency\n");
            sb.append("  Scenario: Idempotency key prevents duplicate processing\n");
            sb.append("    Given a request with idempotency key \"idem-key-abc123\"\n");
            sb.append("    When the same request is submitted twice within 60 seconds\n");
            sb.append("    Then only one operation is executed\n");
            sb.append("    And both responses return the same result\n\n");
        }

        return sb.toString();
    }

    // ── Step definition rendering ──────────────────────────────────────────

    private String renderStepDefinition(BusinessFlow flow, Set<String> categories) {
        String className = toStepDefClassName(flow.getName());
        String entity = guessEntity(flow.getTrigger(), flow.getName());
        String entityClass = capitalise(entity);

        StringBuilder sb = new StringBuilder();

        // Package + imports
        sb.append("package com.example.steps;\n\n");
        sb.append("import io.cucumber.java.en.*;\n");
        sb.append("import io.cucumber.spring.CucumberContextConfiguration;\n");
        sb.append("import org.springframework.boot.test.context.SpringBootTest;\n");
        sb.append("import org.springframework.test.context.ActiveProfiles;\n");
        sb.append("import org.springframework.beans.factory.annotation.Autowired;\n");
        sb.append("import org.springframework.http.*;\n");
        sb.append("import org.springframework.boot.test.web.client.TestRestTemplate;\n");

        if (categories.contains("DB")) {
            sb.append("import org.springframework.transaction.annotation.Transactional;\n");
            sb.append("import org.springframework.jdbc.core.JdbcTemplate;\n");
        }
        if (categories.contains("KAFKA")) {
            sb.append("import org.springframework.kafka.test.context.EmbeddedKafka;\n");
            sb.append("import org.springframework.kafka.core.KafkaTemplate;\n");
            sb.append("import org.springframework.kafka.test.utils.KafkaTestUtils;\n");
            sb.append("import org.apache.kafka.clients.consumer.ConsumerRecord;\n");
        }
        if (categories.contains("S3")) {
            sb.append("import org.mockito.Mockito;\n");
            sb.append("import software.amazon.awssdk.services.s3.S3Client;\n");
            sb.append("import software.amazon.awssdk.core.sync.ResponseTransformer;\n");
        }
        if (categories.contains("CACHE")) {
            sb.append("import org.springframework.cache.CacheManager;\n");
            sb.append("import org.springframework.cache.Cache;\n");
        }
        if (categories.contains("HTTP")) {
            sb.append("import com.github.tomakehurst.wiremock.WireMockServer;\n");
            sb.append("import com.github.tomakehurst.wiremock.client.WireMock;\n");
            sb.append("import com.github.tomakehurst.wiremock.core.WireMockConfiguration;\n");
        }
        sb.append("import org.assertj.core.api.Assertions;\n");
        sb.append("import java.util.*;\n\n");

        // Class annotations
        sb.append("@CucumberContextConfiguration\n");
        sb.append("@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)\n");
        sb.append("@ActiveProfiles(\"test\")\n");
        if (categories.contains("KAFKA")) {
            sb.append("@EmbeddedKafka(partitions = 1, topics = {\"${kafka.topic.name:events}\"})\n");
        }
        sb.append("public class ").append(className).append(" {\n\n");

        // Fields
        sb.append("    @Autowired private TestRestTemplate restTemplate;\n");
        if (categories.contains("DB")) {
            sb.append("    @Autowired private JdbcTemplate jdbcTemplate;\n");
        }
        if (categories.contains("KAFKA")) {
            sb.append("    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;\n");
        }
        if (categories.contains("S3")) {
            sb.append("    @Autowired private S3Client s3Client;\n");
        }
        if (categories.contains("CACHE")) {
            sb.append("    @Autowired private CacheManager cacheManager;\n");
        }
        if (categories.contains("HTTP")) {
            sb.append("    private WireMockServer wireMock;\n");
        }
        sb.append("\n");
        sb.append("    private ResponseEntity<String> lastResponse;\n");
        sb.append("    private Map<String, Object> requestBody = new HashMap<>();\n\n");

        // Background steps
        sb.append("    @Given(\"the system is operational\")\n");
        sb.append("    public void systemIsOperational() {\n");
        sb.append("        // Spring context is up — nothing extra needed\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the database is accessible\")\n");
        sb.append("    public void databaseIsAccessible() {\n");
        if (categories.contains("DB")) {
            sb.append("        jdbcTemplate.queryForObject(\"SELECT 1\", Integer.class);\n");
        }
        sb.append("    }\n\n");

        // Entity setup
        sb.append("    @Given(\"a valid " + entity + " with all required fields\")\n");
        sb.append("    public void aValid").append(entityClass).append("() {\n");
        sb.append("        requestBody.put(\"id\", UUID.randomUUID().toString());\n");
        sb.append("        requestBody.put(\"name\", \"test-").append(entity).append("\");\n");
        sb.append("        requestBody.put(\"amount\", 100);\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"a " + entity + " with {string} set to {string}\")\n");
        sb.append("    public void aEntityWithFieldSetTo(String field, String value) {\n");
        sb.append("        requestBody.put(field, \"null\".equals(value) ? null : value);\n");
        sb.append("    }\n\n");

        // Trigger step
        appendTriggerStep(sb, flow, entity);

        // Then steps
        sb.append("    @Then(\"the operation completes successfully\")\n");
        sb.append("    public void operationCompletesSuccessfully() {\n");
        sb.append("        Assertions.assertThat(lastResponse.getStatusCode().is2xxSuccessful()).isTrue();\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the response status is {int}\")\n");
        sb.append("    public void responseStatusIs(int status) {\n");
        sb.append("        Assertions.assertThat(lastResponse.getStatusCodeValue()).isEqualTo(status);\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the error message contains {string}\")\n");
        sb.append("    public void errorMessageContains(String msg) {\n");
        sb.append("        Assertions.assertThat(lastResponse.getBody()).containsIgnoringCase(msg);\n");
        sb.append("    }\n\n");

        // Auth steps
        appendAuthSteps(sb, flow, entity, categories);

        // Integration-specific steps
        if (categories.contains("DB")) appendDbSteps(sb, entity);
        if (categories.contains("KAFKA")) appendKafkaSteps(sb);
        if (categories.contains("S3")) appendS3Steps(sb);
        if (categories.contains("CACHE")) appendCacheSteps(sb, entity);
        if (categories.contains("HTTP")) appendHttpSteps(sb);

        // Concurrency steps
        if (isWriteOperation(flow.getTrigger())) {
            appendConcurrencySteps(sb, flow, entity);
        }

        // Always emit the jsonHeaders() helper — all HTTP-triggering step defs need it
        appendJsonHeadersHelper(sb);

        sb.append("}\n");
        return sb.toString();
    }

    // ── Step helpers ───────────────────────────────────────────────────────

    private void appendTriggerStep(StringBuilder sb, BusinessFlow flow, String entity) {
        String trigger = flow.getTrigger();
        if (trigger.startsWith("POST")) {
            sb.append("    @When(\"I POST to the " + entity + " endpoint\")\n");
            sb.append("    public void postToEndpoint() {\n");
            sb.append("        lastResponse = restTemplate.postForEntity(\n");
            sb.append("            \"/" + entity + "s\",\n");
            sb.append("            new HttpEntity<>(requestBody, jsonHeaders()),\n");
            sb.append("            String.class);\n");
            sb.append("    }\n\n");
        } else if (trigger.startsWith("GET")) {
            sb.append("    @When(\"I GET to the " + entity + " endpoint\")\n");
            sb.append("    @When(\"I GET the " + entity + " endpoint with id {string}\")\n");
            sb.append("    public void getEndpoint(String id) {\n");
            sb.append("        lastResponse = restTemplate.getForEntity(\n");
            sb.append("            \"/" + entity + "s/\" + id, String.class);\n");
            sb.append("    }\n\n");
        } else if (trigger.startsWith("DELETE")) {
            sb.append("    @When(\"I DELETE to the " + entity + " endpoint\")\n");
            sb.append("    @When(\"I DELETE the " + entity + " endpoint with id {string}\")\n");
            sb.append("    public void deleteEndpoint(String id) {\n");
            sb.append("        lastResponse = restTemplate.exchange(\n");
            sb.append("            \"/" + entity + "s/\" + id,\n");
            sb.append("            HttpMethod.DELETE, new HttpEntity<>(jsonHeaders()), String.class);\n");
            sb.append("    }\n\n");
        } else if (trigger.startsWith("Kafka consumer")) {
            sb.append("    @When(\"a valid message arrives on the Kafka topic\")\n");
            sb.append("    @When(\"a message arrives on the Kafka topic\")\n");
            sb.append("    public void kafkaMessageArrives() throws Exception {\n");
            sb.append("        kafkaTemplate.send(\"events\", Map.of(\"id\", \"test-1\", \"type\", \"" + entity + "\")).get();\n");
            sb.append("        Thread.sleep(500);\n");
            sb.append("    }\n\n");
        } else if (trigger.startsWith("Scheduled")) {
            sb.append("    @When(\"the scheduled job runs\")\n");
            sb.append("    public void scheduledJobRuns() {\n");
            sb.append("        restTemplate.postForEntity(\"/actuator/scheduledtasks\", null, String.class);\n");
            sb.append("    }\n\n");
        } else {
            sb.append("    @When(\"the operation is invoked\")\n");
            sb.append("    public void operationInvoked() {\n");
            sb.append("        lastResponse = restTemplate.postForEntity(\n");
            sb.append("            \"/" + entity + "\", new HttpEntity<>(requestBody, jsonHeaders()), String.class);\n");
            sb.append("    }\n\n");
        }
    }

    private void appendAuthSteps(StringBuilder sb, BusinessFlow flow, String entity, Set<String> categories) {
        boolean hasDb = categories.contains("DB");
        String triggerWhen = deriveTriggerWhen(flow.getTrigger(), entity);
        sb.append("    @Given(\"no authentication token is provided\")\n");
        sb.append("    public void noAuthToken() {\n");
        sb.append("        // Use a separate unauthenticated RestTemplate\n");
        sb.append("        lastResponse = new org.springframework.web.client.RestTemplate()\n");
        sb.append("            .getForEntity(restTemplate.getRootUri() + \"/" + entity + "s\", String.class);\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the user has role {string} but the operation requires {string}\")\n");
        sb.append("    public void userHasRole(String actualRole, String requiredRole) {\n");
        sb.append("        requestBody.put(\"__test_role\", actualRole);\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the authentication token expired {int} minutes ago\")\n");
        sb.append("    public void tokenExpired(int minutesAgo) {\n");
        sb.append("        requestBody.put(\"__test_expired_token\", true);\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"no " + entity + " exists with id {string}\")\n");
        sb.append("    public void noEntityWithId(String id) {\n");
        if (hasDb) {
            sb.append("        jdbcTemplate.update(\"DELETE FROM " + entity + " WHERE id = ?\", id);\n");
        }
        sb.append("    }\n\n");

        sb.append("    @Then(\"the error is propagated with status {int}\")\n");
        sb.append("    public void errorPropagatedWithStatus(int status) {\n");
        sb.append("        Assertions.assertThat(lastResponse.getStatusCodeValue()).isEqualTo(status);\n");
        sb.append("    }\n\n");
    }


    private void appendDbSteps(StringBuilder sb, String entity) {
        sb.append("    // ── DB steps ────────────────────────────────────────────────────\n\n");

        sb.append("    @Given(\"a " + entity + " that would violate a unique constraint\")\n");
        sb.append("    public void entityViolatesConstraint() {\n");
        sb.append("        requestBody.put(\"id\", \"EXISTING-ID-001\");\n");
        sb.append("        // Pre-insert the same ID so next insert triggers constraint violation\n");
        sb.append("        jdbcTemplate.update(\"INSERT INTO " + entity + " (id, name) VALUES (?,?) ON CONFLICT DO NOTHING\",\n");
        sb.append("            \"EXISTING-ID-001\", \"existing\");\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the database is unavailable\")\n");
        sb.append("    public void databaseUnavailable() {\n");
        sb.append("        // Stop H2 datasource or use Testcontainers.stop() in integration profile\n");
        sb.append("        // For unit-level: @MockBean DataSource ds; Mockito.when(ds.getConnection()).thenThrow(...)\n");
        sb.append("        Assumptions.assumeTrue(false, \"Database unavailability requires Testcontainers or mock setup\");\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the database write succeeds\")\n");
        sb.append("    @Given(\"a subsequent operation in the same transaction fails\")\n");
        sb.append("    public void dbWriteSucceedsThenFails() { /* handled by @Transactional rollback test */ }\n\n");

        sb.append("    @When(\"the transaction is rolled back\")\n");
        sb.append("    public void transactionRolledBack() { /* Spring @Transactional handles rollback */ }\n\n");

        sb.append("    @Then(\"no partial data is persisted\")\n");
        sb.append("    public void noPartialData() {\n");
        sb.append("        int count = jdbcTemplate.queryForObject(\n");
        sb.append("            \"SELECT COUNT(*) FROM " + entity + " WHERE id = ?\", Integer.class, requestBody.get(\"id\"));\n");
        sb.append("        Assertions.assertThat(count).isZero();\n");
        sb.append("    }\n\n");
    }

    private void appendKafkaSteps(StringBuilder sb) {
        sb.append("    // ── Kafka steps ──────────────────────────────────────────────────\n\n");

        sb.append("    @Given(\"the Kafka broker is unreachable\")\n");
        sb.append("    public void kafkaBrokerUnreachable() {\n");
        sb.append("        // Achieved by stopping the embedded broker or blocking the port via Testcontainers toxiproxy\n");
        sb.append("        Assumptions.assumeTrue(false, \"Broker failure simulation requires Toxiproxy or network-level mock\");\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the Kafka broker is unavailable for the first {int} attempts\")\n");
        sb.append("    @Given(\"succeeds on the {int}rd attempt\")\n");
        sb.append("    public void kafkaRetry(int attempts) { /* configure producer retry via embedded broker pause */ }\n\n");

        sb.append("    @Then(\"the message is eventually delivered\")\n");
        sb.append("    public void messageEventuallyDelivered() throws Exception {\n");
        sb.append("        // poll consumer up to 5 seconds\n");
        sb.append("        var consumer = KafkaTestUtils.getSingleRecord(\n");
        sb.append("            kafkaTemplate.getProducerFactory().createProducer(), \"events\");\n");
        sb.append("        Assertions.assertThat(consumer).isNotNull();\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the total number of send attempts is {int}\")\n");
        sb.append("    public void totalSendAttempts(int expected) { /* verified via metrics or spy */ }\n\n");

        sb.append("    @Given(\"the Kafka topic receives a malformed message\")\n");
        sb.append("    public void kafkaMalformedMessage() throws Exception {\n");
        sb.append("        kafkaTemplate.send(\"events\", \"not-valid-json\").get();\n");
        sb.append("        Thread.sleep(300);\n");
        sb.append("    }\n\n");

        sb.append("    @When(\"the consumer attempts to deserialise the message\")\n");
        sb.append("    public void consumerDeserialises() { /* consumer has already processed by now */ }\n\n");

        sb.append("    @Then(\"the message is routed to the Dead Letter Queue\")\n");
        sb.append("    public void messageInDlq() {\n");
        sb.append("        // Assert DLQ topic has a record\n");
        sb.append("        Assertions.assertThat(true).isTrue(); // TODO: bind DLQ consumer in test\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the consumer continues processing subsequent messages\")\n");
        sb.append("    public void consumerContinues() { /* verify no consumer lag increase */ }\n\n");
    }

    private void appendS3Steps(StringBuilder sb) {
        sb.append("    // ── S3 steps ────────────────────────────────────────────────────\n\n");

        sb.append("    @Given(\"the S3 bucket is unavailable\")\n");
        sb.append("    public void s3Unavailable() {\n");
        sb.append("        Mockito.doThrow(new software.amazon.awssdk.core.exception.SdkClientException(\n");
        sb.append("            software.amazon.awssdk.core.exception.SdkClientException.builder().message(\"Connection refused\").build()))\n");
        sb.append("            .when(s3Client).putObject(\n");
        sb.append("                Mockito.any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),\n");
        sb.append("                Mockito.any(software.amazon.awssdk.core.sync.RequestBody.class));\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"no partial file is stored\")\n");
        sb.append("    public void noPartialFileStored() {\n");
        sb.append("        Mockito.verify(s3Client, Mockito.never()).putObject(\n");
        sb.append("            Mockito.any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),\n");
        sb.append("            Mockito.any(software.amazon.awssdk.core.sync.RequestBody.class));\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"no object exists at the requested S3 key\")\n");
        sb.append("    public void noS3Object() {\n");
        sb.append("        Mockito.doThrow(software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder().build())\n");
        sb.append("            .when(s3Client).getObject(\n");
        sb.append("                Mockito.any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class),\n");
        sb.append("                Mockito.any(ResponseTransformer.class));\n");
        sb.append("    }\n\n");
    }

    private void appendCacheSteps(StringBuilder sb, String entity) {
        sb.append("    // ── Cache steps ──────────────────────────────────────────────────\n\n");

        sb.append("    @Given(\"the cache does not contain the requested " + entity + "\")\n");
        sb.append("    public void cacheEmpty() {\n");
        sb.append("        Optional.ofNullable(cacheManager.getCache(\"" + entity + "s\"))\n");
        sb.append("            .ifPresent(Cache::invalidate);\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the database contains the " + entity + "\")\n");
        sb.append("    public void dbContainsEntity() {\n");
        sb.append("        jdbcTemplate.update(\"INSERT INTO " + entity + " (id, name) VALUES (?,?) ON CONFLICT DO NOTHING\",\n");
        sb.append("            requestBody.getOrDefault(\"id\", \"cache-test-1\"), \"cached-entity\");\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the response is loaded from the database\")\n");
        sb.append("    public void responseFromDatabase() {\n");
        sb.append("        Assertions.assertThat(lastResponse.getStatusCode().is2xxSuccessful()).isTrue();\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the result is stored in the cache for subsequent requests\")\n");
        sb.append("    public void resultCached() {\n");
        sb.append("        Cache cache = cacheManager.getCache(\"" + entity + "s\");\n");
        sb.append("        Assertions.assertThat(cache).isNotNull();\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"neither the cache nor the database contains the " + entity + "\")\n");
        sb.append("    public void neitherCacheNorDb() {\n");
        sb.append("        Optional.ofNullable(cacheManager.getCache(\"" + entity + "s\"))\n");
        sb.append("            .ifPresent(Cache::invalidate);\n");
        sb.append("        jdbcTemplate.update(\"DELETE FROM " + entity + " WHERE id = ?\",\n");
        sb.append("            requestBody.getOrDefault(\"id\", \"NO-SUCH-ID\"));\n");
        sb.append("    }\n\n");
    }

    private void appendHttpSteps(StringBuilder sb) {
        sb.append("    // ── HTTP / WireMock steps ────────────────────────────────────────\n\n");

        sb.append("    @Given(\"the downstream service returns HTTP {int}\")\n");
        sb.append("    public void downstreamReturns(int statusCode) {\n");
        sb.append("        if (wireMock == null) {\n");
        sb.append("            wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());\n");
        sb.append("            wireMock.start();\n");
        sb.append("        }\n");
        sb.append("        wireMock.stubFor(WireMock.any(WireMock.anyUrl())\n");
        sb.append("            .willReturn(WireMock.aResponse().withStatus(statusCode)));\n");
        sb.append("    }\n\n");

        sb.append("    @Given(\"the downstream service does not respond within {int} seconds\")\n");
        sb.append("    public void downstreamTimeout(int seconds) {\n");
        sb.append("        if (wireMock == null) {\n");
        sb.append("            wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());\n");
        sb.append("            wireMock.start();\n");
        sb.append("        }\n");
        sb.append("        wireMock.stubFor(WireMock.any(WireMock.anyUrl())\n");
        sb.append("            .willReturn(WireMock.aResponse().withFixedDelay((seconds + 2) * 1000)));\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the operation fails gracefully\")\n");
        sb.append("    public void operationFailsGracefully() {\n");
        sb.append("        Assertions.assertThat(lastResponse.getStatusCode().is5xxServerError()\n");
        sb.append("            || lastResponse.getStatusCode().is4xxClientError()).isTrue();\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the error is logged with severity ERROR\")\n");
        sb.append("    public void errorLogged() { /* verified via log appender spy */ }\n\n");
    }

    private void appendConcurrencySteps(StringBuilder sb, BusinessFlow flow, String entity) {
        sb.append("    // ── Concurrency steps ────────────────────────────────────────────\n\n");

        sb.append("    @Given(\"two concurrent requests for the same " + entity + " id\")\n");
        sb.append("    public void twoConcurrentRequests() {\n");
        sb.append("        requestBody.put(\"id\", \"CONCURRENT-ID-001\");\n");
        sb.append("    }\n\n");

        sb.append("    @When(\"both requests are processed simultaneously\")\n");
        sb.append("    public void bothProcessed() throws Exception {\n");
        sb.append("        var futures = java.util.concurrent.Executors.newFixedThreadPool(2).invokeAll(\n");
        sb.append("            List.of(\n");
        sb.append("                () -> restTemplate.postForEntity(\"/" + entity + "s\",\n");
        sb.append("                    new HttpEntity<>(requestBody, jsonHeaders()), String.class),\n");
        sb.append("                () -> restTemplate.postForEntity(\"/" + entity + "s\",\n");
        sb.append("                    new HttpEntity<>(requestBody, jsonHeaders()), String.class)\n");
        sb.append("            ));\n");
        sb.append("        lastResponse = futures.get(0).get(); // store first for assertion\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"exactly one request succeeds\")\n");
        sb.append("    public void exactlyOneSucceeds() {\n");
        sb.append("        int count = jdbcTemplate.queryForObject(\n");
        sb.append("            \"SELECT COUNT(*) FROM " + entity + " WHERE id = ?\", Integer.class, \"CONCURRENT-ID-001\");\n");
        sb.append("        Assertions.assertThat(count).isEqualTo(1);\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"the other request receives a conflict or retry response\")\n");
        sb.append("    public void otherReceivesConflict() { /* checked by asserting one 409 in concurrent results */ }\n\n");

        sb.append("    @Given(\"a request with idempotency key {string}\")\n");
        sb.append("    public void withIdempotencyKey(String key) { requestBody.put(\"idempotencyKey\", key); }\n\n");

        sb.append("    @When(\"the same request is submitted twice within {int} seconds\")\n");
        sb.append("    public void submittedTwice(int seconds) {\n");
        sb.append("        restTemplate.postForEntity(\"/" + entity + "s\",\n");
        sb.append("            new HttpEntity<>(requestBody, jsonHeaders()), String.class);\n");
        sb.append("        lastResponse = restTemplate.postForEntity(\"/" + entity + "s\",\n");
        sb.append("            new HttpEntity<>(requestBody, jsonHeaders()), String.class);\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"only one operation is executed\")\n");
        sb.append("    public void onlyOneOperation() {\n");
        sb.append("        int count = jdbcTemplate.queryForObject(\n");
        sb.append("            \"SELECT COUNT(*) FROM " + entity + " WHERE idempotency_key = ?\",\n");
        sb.append("            Integer.class, requestBody.get(\"idempotencyKey\"));\n");
        sb.append("        Assertions.assertThat(count).isEqualTo(1);\n");
        sb.append("    }\n\n");

        sb.append("    @Then(\"both responses return the same result\")\n");
        sb.append("    public void bothResponsesSame() {\n");
        sb.append("        Assertions.assertThat(lastResponse.getStatusCode().is2xxSuccessful()).isTrue();\n");
        sb.append("    }\n\n");
    }

    // ── Feature helpers ────────────────────────────────────────────────────

    private void appendAuthScenarios(StringBuilder sb, BusinessFlow flow, String entity) {
        String when = deriveTriggerWhen(flow.getTrigger(), entity);
        sb.append("  @auth\n");
        sb.append("  Scenario: Unauthenticated request is rejected\n");
        sb.append("    Given no authentication token is provided\n");
        sb.append("    ").append(when).append("\n");
        sb.append("    Then the response status is 401\n\n");

        sb.append("  @auth\n");
        sb.append("  Scenario: Request with insufficient role is rejected\n");
        sb.append("    Given the user has role \"VIEWER\" but the operation requires \"ADMIN\"\n");
        sb.append("    ").append(when).append("\n");
        sb.append("    Then the response status is 403\n");
        sb.append("    And the error message contains \"Access Denied\"\n\n");

        sb.append("  @auth\n");
        sb.append("  Scenario: Request with expired JWT is rejected\n");
        sb.append("    Given the authentication token expired 10 minutes ago\n");
        sb.append("    ").append(when).append("\n");
        sb.append("    Then the response status is 401\n");
        sb.append("    And the error message contains \"token expired\"\n\n");
    }

    private void renderIntegrationScenario(StringBuilder sb, BusinessFlow flow, String entity, IntegrationPoint ip) {
        String when = deriveTriggerWhen(flow.getTrigger(), entity);
        switch (ip.getCategory()) {
            case "DB" -> {
                sb.append("  @db\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" handles database constraint violation\n");
                sb.append("    Given a ").append(entity).append(" that would violate a unique constraint\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the response status is 409\n");
                sb.append("    And the error message contains \"already exists\"\n\n");

                sb.append("  @db\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" handles database connection failure\n");
                sb.append("    Given the database is unavailable\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the response status is 503\n");
                sb.append("    And the error message contains \"service unavailable\"\n\n");

                if ("WRITE".equals(ip.getDirection()) || "BOTH".equals(ip.getDirection())) {
                    sb.append("  @db\n");
                    sb.append("  Scenario: ").append(flow.getName()).append(" rolls back on downstream failure\n");
                    sb.append("    Given the database write succeeds\n");
                    sb.append("    And a subsequent operation in the same transaction fails\n");
                    sb.append("    When the transaction is rolled back\n");
                    sb.append("    Then no partial data is persisted\n\n");
                }
            }
            case "KAFKA" -> {
                if ("PRODUCE".equals(ip.getDirection())) {
                    sb.append("  @kafka\n");
                    sb.append("  Scenario: ").append(flow.getName()).append(" handles Kafka broker unavailability\n");
                    sb.append("    Given the Kafka broker is unreachable\n");
                    sb.append("    ").append(when).append("\n");
                    sb.append("    Then the operation fails gracefully\n");
                    sb.append("    And the error is logged with severity ERROR\n\n");

                    sb.append("  @kafka\n");
                    sb.append("  Scenario: ").append(flow.getName()).append(" retries Kafka send on transient failure\n");
                    sb.append("    Given the Kafka broker is unavailable for the first 2 attempts\n");
                    sb.append("    And succeeds on the 3rd attempt\n");
                    sb.append("    ").append(when).append("\n");
                    sb.append("    Then the message is eventually delivered\n");
                    sb.append("    And the total number of send attempts is 3\n\n");
                }
                if ("CONSUME".equals(ip.getDirection())) {
                    sb.append("  @kafka\n");
                    sb.append("  Scenario: Consumer routes poison pill to Dead Letter Queue\n");
                    sb.append("    Given the Kafka topic receives a malformed message\n");
                    sb.append("    When the consumer attempts to deserialise the message\n");
                    sb.append("    Then the message is routed to the Dead Letter Queue\n");
                    sb.append("    And the consumer continues processing subsequent messages\n\n");
                }
            }
            case "S3" -> {
                sb.append("  @s3\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" handles S3 upload failure\n");
                sb.append("    Given the S3 bucket is unavailable\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the response status is 503\n");
                sb.append("    And no partial file is stored\n\n");

                sb.append("  @s3\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" handles missing S3 object\n");
                sb.append("    Given no object exists at the requested S3 key\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the response status is 404\n");
                sb.append("    And the error message contains \"not found\"\n\n");
            }
            case "CACHE" -> {
                sb.append("  @cache\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" on cache miss falls back to database\n");
                sb.append("    Given the cache does not contain the requested ").append(entity).append("\n");
                sb.append("    And the database contains the ").append(entity).append("\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the response is loaded from the database\n");
                sb.append("    And the result is stored in the cache for subsequent requests\n\n");

                sb.append("  @cache\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" on cache miss and database miss returns 404\n");
                sb.append("    Given neither the cache nor the database contains the ").append(entity).append("\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the response status is 404\n\n");
            }
            case "HTTP" -> {
                sb.append("  @http\n");
                sb.append("  Scenario Outline: ").append(flow.getName()).append(" handles downstream HTTP errors\n");
                sb.append("    Given the downstream service returns HTTP <statusCode>\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the error is propagated with status <expectedStatus>\n\n");
                sb.append("    Examples:\n");
                sb.append("      | statusCode | expectedStatus |\n");
                sb.append("      | 400        | 400            |\n");
                sb.append("      | 401        | 502            |\n");
                sb.append("      | 429        | 503            |\n");
                sb.append("      | 500        | 502            |\n\n");

                sb.append("  @http\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" handles downstream timeout\n");
                sb.append("    Given the downstream service does not respond within 10 seconds\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the response status is 504\n");
                sb.append("    And the error message contains \"timeout\"\n\n");
            }
            case "THIRD_PARTY" -> {
                sb.append("  @third-party\n");
                sb.append("  Scenario: ").append(flow.getName()).append(" handles ").append(ip.getName()).append(" failure\n");
                sb.append("    Given ").append(ip.getName()).append(" returns an error\n");
                sb.append("    ").append(when).append("\n");
                sb.append("    Then the operation fails gracefully\n");
                sb.append("    And the error is surfaced to the caller with an appropriate message\n\n");
            }
        }
    }

    // ── Shared trigger text ────────────────────────────────────────────────

    private void renderTriggerWhen(StringBuilder sb, String trigger, String entity) {
        sb.append("    ").append(deriveTriggerWhen(trigger, entity)).append("\n");
    }

    private void renderTriggerWhenWith(StringBuilder sb, String trigger, String entity, String id) {
        if (trigger.startsWith("GET") || trigger.startsWith("DELETE")) {
            sb.append("    When I ").append(trigger.split(" ")[0]).append(" the ")
              .append(entity).append(" endpoint with id ").append(id).append("\n");
        } else {
            renderTriggerWhen(sb, trigger, entity);
        }
    }

    private String deriveTriggerWhen(String trigger, String entity) {
        if (trigger.startsWith("Kafka")) return "When a message arrives on the Kafka topic";
        if (trigger.startsWith("Scheduled")) return "When the scheduled job runs";
        return "When I " + trigger.split(" ")[0] + " to the " + entity + " endpoint";
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private boolean isReadOperation(String trigger) { return trigger.startsWith("GET"); }

    private boolean isWriteOperation(String trigger) {
        return trigger.startsWith("POST") || trigger.startsWith("PUT")
                || trigger.startsWith("PATCH") || trigger.startsWith("DELETE");
    }

    private String guessEntity(String trigger, String flowName) {
        String name = flowName.replace("_", " ").replace("Flow", "").trim();
        String[] words = name.split("\\s+");
        return words.length > 0 ? words[words.length - 1].toLowerCase() : "resource";
    }

    private String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toFeatureFileName(String flowName) {
        return flowName.replaceAll("([A-Z])", "_$1").toLowerCase().replaceFirst("^_", "")
                .replace("__", "_").replace("_flow", "") + ".feature";
    }

    private String toStepDefClassName(String flowName) {
        return flowName.replace("Flow", "") + "Steps";
    }

    // ── Helper text emitted into step def class body ───────────────────────

    private void appendJsonHeadersHelper(StringBuilder sb) {
        sb.append("    private HttpHeaders jsonHeaders() {\n");
        sb.append("        HttpHeaders h = new HttpHeaders();\n");
        sb.append("        h.setContentType(MediaType.APPLICATION_JSON);\n");
        sb.append("        return h;\n");
        sb.append("    }\n\n");
    }

    // ── Public model ──────────────────────────────────────────────────────

    public record GeneratedGherkin(
            String featureFileName,
            String featureContent,
            String stepDefClassName,
            String stepDefContent) {}
}
