package com.repoinsight.static_analysis;

import com.repoinsight.analyzer.model.IntegrationPoint;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedField;
import com.repoinsight.static_analysis.model.ParsedMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticIntegrationDetectorTest {

    private StaticIntegrationDetector detector;

    @BeforeEach
    void setUp() { detector = new StaticIntegrationDetector(); }

    // ── DB detection ───────────────────────────────────────────────────────

    @Test
    @DisplayName("JdbcTemplate field → DB integration detected")
    void db_jdbcTemplateField() {
        ParsedClass cls = serviceClass("OrderService",
                List.of(field("JdbcTemplate", "jdbc")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "DB".equals(p.getCategory()));
        assertThat(points).anyMatch(p -> p.getName().contains("JdbcTemplate"));
    }

    @Test
    @DisplayName("EntityManager field → DB integration detected")
    void db_entityManagerField() {
        ParsedClass cls = serviceClass("OrderService",
                List.of(field("EntityManager", "em")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "DB".equals(p.getCategory()));
    }

    @Test
    @DisplayName("Generic JpaRepository field → DB integration detected")
    void db_jpaRepositoryGenericField() {
        ParsedClass cls = serviceClass("OrderService",
                List.of(field("JpaRepository<Order, Long>", "repo")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "DB".equals(p.getCategory()));
    }

    @Test
    @DisplayName("Class implementing JpaRepository → DB integration detected")
    void db_implementsJpaRepository() {
        ParsedClass cls = ParsedClass.builder()
                .simpleName("OrderRepository")
                .layer("REPOSITORY")
                .classAnnotations(List.of())
                .implementedInterfaces(List.of("JpaRepository<Order, Long>"))
                .superClass(null)
                .fields(List.of())
                .methods(List.of())
                .isInterface(true)
                .isAbstract(false)
                .isEnum(false)
                .build();

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "DB".equals(p.getCategory()));
    }

    @Test
    @DisplayName("Read-only repo methods → direction is READ")
    void db_readOnlyRepo_directionRead() {
        ParsedClass cls = ParsedClass.builder()
                .simpleName("ProductRepository")
                .layer("REPOSITORY")
                .classAnnotations(List.of())
                .implementedInterfaces(List.of("JpaRepository<Product, Long>"))
                .superClass(null)
                .fields(List.of(field("JpaRepository<Product, Long>", "repo")))
                .methods(List.of(
                        method("findById", List.of("Long"), true, List.of()),
                        method("findAll", List.of(), true, List.of())
                ))
                .isInterface(false)
                .isAbstract(false)
                .isEnum(false)
                .build();

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "DB".equals(p.getCategory()) && "READ".equals(p.getDirection()));
    }

    // ── Kafka detection ────────────────────────────────────────────────────

    @Test
    @DisplayName("KafkaTemplate field → KAFKA PRODUCE integration")
    void kafka_producerField() {
        ParsedClass cls = serviceClass("NotificationService",
                List.of(field("KafkaTemplate<String, Object>", "kafkaTemplate")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "KAFKA".equals(p.getCategory()) && "PRODUCE".equals(p.getDirection()));
    }

    @Test
    @DisplayName("@KafkaListener method annotation → KAFKA CONSUME integration")
    void kafka_consumerAnnotation() {
        ParsedMethod listener = ParsedMethod.builder()
                .name("handleOrderEvent")
                .returnType("void")
                .parameterTypes(List.of("OrderEvent"))
                .annotations(List.of("KafkaListener"))
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();

        ParsedClass cls = serviceClass("OrderEventConsumer", List.of(), List.of(listener));

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "KAFKA".equals(p.getCategory()) && "CONSUME".equals(p.getDirection()));
        assertThat(points).anyMatch(p -> "KAFKA".equals(p.getCategory())
                && p.getDetectionPattern().contains("KafkaListener"));
    }

    // ── S3 detection ───────────────────────────────────────────────────────

    @Test
    @DisplayName("AmazonS3 field → S3 integration detected")
    void s3_amazonS3Field() {
        ParsedClass cls = serviceClass("StorageService",
                List.of(field("AmazonS3", "s3Client")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "S3".equals(p.getCategory()));
    }

    @Test
    @DisplayName("S3Client field → S3 integration detected")
    void s3_s3ClientField() {
        ParsedClass cls = serviceClass("FileService",
                List.of(field("S3Client", "s3")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "S3".equals(p.getCategory()));
    }

    @Test
    @DisplayName("putObject call in method body → S3 integration detected")
    void s3_putObjectCallSite() {
        ParsedMethod upload = ParsedMethod.builder()
                .name("uploadFile")
                .returnType("String")
                .parameterTypes(List.of("MultipartFile"))
                .annotations(List.of())
                .calledMethods(List.of("putObject", "generatePresignedUrl"))
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();

        ParsedClass cls = serviceClass("DocumentService", List.of(), List.of(upload));

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "S3".equals(p.getCategory())
                && p.getDetectionPattern().contains("S3 API call"));
    }

    // ── Cache detection ────────────────────────────────────────────────────

    @Test
    @DisplayName("RedisTemplate field → CACHE integration detected")
    void cache_redisTemplateField() {
        ParsedClass cls = serviceClass("SessionService",
                List.of(field("RedisTemplate<String, Object>", "redisTemplate")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "CACHE".equals(p.getCategory()));
    }

    @Test
    @DisplayName("@Cacheable method annotation → CACHE READ integration")
    void cache_cacheableAnnotation() {
        ParsedMethod cachedMethod = ParsedMethod.builder()
                .name("findProduct")
                .returnType("Product")
                .parameterTypes(List.of("Long"))
                .annotations(List.of("Cacheable"))
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();

        ParsedClass cls = serviceClass("ProductService", List.of(), List.of(cachedMethod));

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "CACHE".equals(p.getCategory()) && "READ".equals(p.getDirection()));
    }

    @Test
    @DisplayName("@CacheEvict annotation → CACHE WRITE integration")
    void cache_cacheEvictAnnotation() {
        ParsedMethod evict = ParsedMethod.builder()
                .name("deleteProduct")
                .returnType("void")
                .parameterTypes(List.of("Long"))
                .annotations(List.of("CacheEvict"))
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();

        ParsedClass cls = serviceClass("ProductService", List.of(), List.of(evict));

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "CACHE".equals(p.getCategory()) && "WRITE".equals(p.getDirection()));
    }

    // ── HTTP detection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("RestTemplate field → HTTP integration detected")
    void http_restTemplateField() {
        ParsedClass cls = serviceClass("PaymentGatewayService",
                List.of(field("RestTemplate", "restTemplate")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "HTTP".equals(p.getCategory())
                && p.getName().contains("RestTemplate"));
    }

    @Test
    @DisplayName("WebClient field → HTTP integration detected")
    void http_webClientField() {
        ParsedClass cls = serviceClass("ExternalApiClient",
                List.of(field("WebClient", "webClient")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "HTTP".equals(p.getCategory())
                && p.getName().contains("WebClient"));
    }

    @Test
    @DisplayName("@FeignClient class annotation → HTTP integration detected")
    void http_feignClientAnnotation() {
        ParsedClass cls = ParsedClass.builder()
                .simpleName("InventoryClient")
                .layer("CLIENT")
                .classAnnotations(List.of("FeignClient"))
                .implementedInterfaces(List.of())
                .superClass(null)
                .fields(List.of())
                .methods(List.of())
                .isInterface(true)
                .isAbstract(false)
                .isEnum(false)
                .build();

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "HTTP".equals(p.getCategory())
                && p.getDetectionPattern().contains("FeignClient"));
    }

    // ── Third-party detection ──────────────────────────────────────────────

    @Test
    @DisplayName("StripeClient field → THIRD_PARTY Stripe SDK detected")
    void thirdParty_stripeClient() {
        ParsedClass cls = serviceClass("PaymentService",
                List.of(field("StripeClient", "stripeClient")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "THIRD_PARTY".equals(p.getCategory())
                && p.getName().contains("Stripe"));
    }

    @Test
    @DisplayName("SendGrid field → THIRD_PARTY email SDK detected")
    void thirdParty_sendGrid() {
        ParsedClass cls = serviceClass("EmailService",
                List.of(field("SendGridClient", "sendGrid")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        assertThat(points).anyMatch(p -> "THIRD_PARTY".equals(p.getCategory()));
    }

    // ── Deduplication ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Two fields of same type → single integration point (no duplicates)")
    void deduplication_sameTypeTwoFields() {
        ParsedClass cls = serviceClass("OrderService",
                List.of(
                        field("KafkaTemplate<String, Object>", "orderKafka"),
                        field("KafkaTemplate<String, Object>", "eventKafka")
                ),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(cls));

        long kafkaCount = points.stream().filter(p -> "KAFKA".equals(p.getCategory())
                && "OrderService".equals(p.getClassRef())).count();
        assertThat(kafkaCount).isEqualTo(1);
    }

    // ── Multi-class ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Multiple classes → integration points from each are all collected")
    void multiClass_allPointsCollected() {
        ParsedClass svc = serviceClass("OrderService",
                List.of(field("KafkaTemplate<String, Object>", "kafka")),
                List.of());
        ParsedClass repo = serviceClass("OrderRepository",
                List.of(field("JdbcTemplate", "jdbc")),
                List.of());

        List<IntegrationPoint> points = detector.detect(List.of(svc, repo));

        assertThat(points).anyMatch(p -> "KAFKA".equals(p.getCategory()));
        assertThat(points).anyMatch(p -> "DB".equals(p.getCategory()));
    }

    @Test
    @DisplayName("Empty class list → no integration points")
    void emptyInput_noPoints() {
        assertThat(detector.detect(List.of())).isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ParsedClass serviceClass(String name, List<ParsedField> fields, List<ParsedMethod> methods) {
        return ParsedClass.builder()
                .simpleName(name)
                .layer("SERVICE")
                .classAnnotations(List.of("Service"))
                .implementedInterfaces(List.of())
                .superClass(null)
                .fields(fields)
                .methods(methods)
                .isInterface(false)
                .isAbstract(false)
                .isEnum(false)
                .build();
    }

    private ParsedField field(String type, String name) {
        return ParsedField.builder().type(type).name(name).annotations(List.of("Autowired")).build();
    }

    private ParsedMethod method(String name, List<String> params, boolean isPublic, List<String> annotations) {
        return ParsedMethod.builder()
                .name(name)
                .returnType("Object")
                .parameterTypes(params)
                .annotations(annotations)
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(isPublic)
                .isStatic(false)
                .build();
    }
}
