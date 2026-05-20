package com.repoinsight.static_analysis;

import com.repoinsight.analyzer.model.IntegrationPoint;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedField;
import com.repoinsight.static_analysis.model.ParsedMethod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects integration points from the AST — no AI needed.
 * Detection is purely structural: field types, class-level annotations,
 * method annotations, and call-site expressions.
 */
@Component
public class StaticIntegrationDetector {

    // DB patterns matched against field types and superclass/interface names
    private static final List<String> DB_TYPES = List.of(
            "JdbcTemplate", "NamedParameterJdbcTemplate", "EntityManager",
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
            "MongoRepository", "DataSource", "R2dbcEntityTemplate"
    );

    // Kafka
    private static final List<String> KAFKA_PRODUCER_TYPES = List.of("KafkaTemplate", "KafkaProducer", "ProducerRecord");
    private static final List<String> KAFKA_CONSUMER_ANNOTATIONS = List.of("KafkaListener", "KafkaHandler");

    // S3 / Object store
    private static final List<String> S3_TYPES = List.of("AmazonS3", "S3Client", "S3AsyncClient", "S3Template", "S3Operations");

    // Cache
    private static final List<String> CACHE_ANNOTATIONS = List.of("Cacheable", "CachePut", "CacheEvict", "Caching");
    private static final List<String> CACHE_TYPES = List.of("RedisTemplate", "StringRedisTemplate", "CacheManager", "LoadingCache", "Cache");

    // HTTP / REST clients
    private static final List<String> HTTP_TYPES = List.of("RestTemplate", "WebClient", "HttpClient", "CloseableHttpClient", "OkHttpClient");
    private static final List<String> HTTP_ANNOTATIONS = List.of("FeignClient");

    // Third-party SDKs (type prefix matching)
    private static final Map<String, String> THIRD_PARTY_PREFIXES = Map.of(
            "Stripe", "Stripe SDK",
            "Twilio", "Twilio SMS SDK",
            "SendGrid", "SendGrid Email SDK",
            "FirebaseApp", "Firebase SDK",
            "AmazonSES", "AWS SES",
            "AmazonSNS", "AWS SNS",
            "AmazonSQS", "AWS SQS",
            "SlackClient", "Slack API",
            "StripeClient", "Stripe SDK"
    );

    public List<IntegrationPoint> detect(List<ParsedClass> classes) {
        List<IntegrationPoint> points = new ArrayList<>();
        for (ParsedClass cls : classes) {
            points.addAll(detectForClass(cls));
        }
        return points;
    }

    private List<IntegrationPoint> detectForClass(ParsedClass cls) {
        List<IntegrationPoint> points = new ArrayList<>();

        // ── Field-based detection ────────────────────────────────────────────
        for (ParsedField field : cls.getFields()) {
            String type = field.getType();
            String baseType = extractBaseType(type);

            if (matchesAny(baseType, DB_TYPES) || isJpaRepo(cls)) {
                String direction = isReadOnlyRepo(cls) ? "READ" : "BOTH";
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("DB").name("Database via " + baseType)
                        .classRef(cls.getSimpleName()).methodRef("(field: " + field.getName() + ")")
                        .detectionPattern("Field type: " + baseType).direction(direction).build());
            } else if (matchesAny(baseType, KAFKA_PRODUCER_TYPES)) {
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("KAFKA").name("Kafka Producer (KafkaTemplate)")
                        .classRef(cls.getSimpleName()).methodRef("(field: " + field.getName() + ")")
                        .detectionPattern("Field type: KafkaTemplate").direction("PRODUCE").build());
            } else if (matchesAny(baseType, S3_TYPES)) {
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("S3").name("S3 Object Storage (" + baseType + ")")
                        .classRef(cls.getSimpleName()).methodRef("(field: " + field.getName() + ")")
                        .detectionPattern("Field type: " + baseType).direction("BOTH").build());
            } else if (matchesAny(baseType, CACHE_TYPES)) {
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("CACHE").name("Cache (" + baseType + ")")
                        .classRef(cls.getSimpleName()).methodRef("(field: " + field.getName() + ")")
                        .detectionPattern("Field type: " + baseType).direction("BOTH").build());
            } else if (matchesAny(baseType, HTTP_TYPES)) {
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("HTTP").name("HTTP Client (" + baseType + ")")
                        .classRef(cls.getSimpleName()).methodRef("(field: " + field.getName() + ")")
                        .detectionPattern("Field type: " + baseType).direction("BOTH").build());
            } else {
                detectThirdParty(cls, field.getName(), baseType).ifPresent(points::add);
            }
        }

        // ── Annotation-based detection ────────────────────────────────────────
        // @FeignClient on the class itself
        if (cls.getClassAnnotations().stream().anyMatch(HTTP_ANNOTATIONS::contains)) {
            addIfAbsent(points, IntegrationPoint.builder()
                    .category("HTTP").name("HTTP via FeignClient")
                    .classRef(cls.getSimpleName()).methodRef("(class-level @FeignClient)")
                    .detectionPattern("@FeignClient").direction("BOTH").build());
        }

        // Method-level annotations
        for (ParsedMethod method : cls.getMethods()) {
            if (method.getAnnotations().stream().anyMatch(KAFKA_CONSUMER_ANNOTATIONS::contains)) {
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("KAFKA").name("Kafka Consumer (@KafkaListener)")
                        .classRef(cls.getSimpleName()).methodRef(method.signature())
                        .detectionPattern("@KafkaListener").direction("CONSUME").build());
            }
            if (method.getAnnotations().stream().anyMatch(CACHE_ANNOTATIONS::contains)) {
                String annotation = method.getAnnotations().stream()
                        .filter(CACHE_ANNOTATIONS::contains).findFirst().orElse("@Cacheable");
                String direction = "@CachePut".equals(annotation) ? "WRITE"
                        : "@CacheEvict".equals(annotation) ? "WRITE" : "READ";
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("CACHE").name("Cache (" + annotation + ")")
                        .classRef(cls.getSimpleName()).methodRef(method.signature())
                        .detectionPattern("@" + annotation).direction(direction).build());
            }
            // S3 call-site detection (method calls like putObject, getObject)
            if (method.getCalledMethods().stream().anyMatch(m -> m.matches("putObject|getObject|deleteObject|copyObject|listObjects|generatePresignedUrl"))) {
                addIfAbsent(points, IntegrationPoint.builder()
                        .category("S3").name("S3 Object Operation")
                        .classRef(cls.getSimpleName()).methodRef(method.signature())
                        .detectionPattern("S3 API call in body").direction("BOTH").build());
            }
        }

        return points;
    }

    private boolean isJpaRepo(ParsedClass cls) {
        return cls.getImplementedInterfaces().stream()
                .anyMatch(i -> i.contains("Repository") || i.contains("JpaRepository") || i.contains("CrudRepository"));
    }

    private boolean isReadOnlyRepo(ParsedClass cls) {
        return cls.getMethods().stream()
                .filter(m -> m.isPublic() && !m.isStatic())
                .noneMatch(m -> m.getName().startsWith("save") || m.getName().startsWith("delete")
                        || m.getName().startsWith("update") || m.getName().startsWith("persist"));
    }

    private java.util.Optional<IntegrationPoint> detectThirdParty(ParsedClass cls, String fieldName, String type) {
        return THIRD_PARTY_PREFIXES.entrySet().stream()
                .filter(e -> type.startsWith(e.getKey()))
                .map(e -> IntegrationPoint.builder()
                        .category("THIRD_PARTY").name(e.getValue())
                        .classRef(cls.getSimpleName()).methodRef("(field: " + fieldName + ")")
                        .detectionPattern("Field type: " + type).direction("BOTH").build())
                .findFirst();
    }

    private void addIfAbsent(List<IntegrationPoint> list, IntegrationPoint point) {
        boolean exists = list.stream().anyMatch(p ->
                p.getCategory().equals(point.getCategory()) && p.getName().equals(point.getName())
                && p.getClassRef().equals(point.getClassRef()));
        if (!exists) list.add(point);
    }

    private boolean matchesAny(String type, List<String> patterns) {
        return patterns.stream().anyMatch(p -> type.equals(p) || type.startsWith(p));
    }

    private String extractBaseType(String genericType) {
        return genericType.replaceAll("<.*>", "").trim();
    }
}
