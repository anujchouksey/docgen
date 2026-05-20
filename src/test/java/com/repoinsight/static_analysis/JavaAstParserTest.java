package com.repoinsight.static_analysis;

import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class JavaAstParserTest {

    private JavaAstParser parser;

    @BeforeEach
    void setUp() { parser = new JavaAstParser(); }

    // ── Layer detection ────────────────────────────────────────────────────

    @Test
    @DisplayName("@Service annotation → SERVICE layer")
    void layer_serviceAnnotation() {
        Optional<ParsedClass> result = parser.parse(file("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder() {}
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getLayer()).isEqualTo("SERVICE");
    }

    @Test
    @DisplayName("@RestController annotation → CONTROLLER layer")
    void layer_restControllerAnnotation() {
        Optional<ParsedClass> result = parser.parse(file("OrderController.java", """
                package com.example;
                @RestController
                public class OrderController {
                    public String getOrder() { return "ok"; }
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getLayer()).isEqualTo("CONTROLLER");
    }

    @Test
    @DisplayName("Extends JpaRepository → REPOSITORY layer")
    void layer_jpaRepositoryInterface() {
        Optional<ParsedClass> result = parser.parse(file("OrderRepository.java", """
                package com.example;
                public interface OrderRepository extends JpaRepository<Order, Long> {}
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getLayer()).isEqualTo("REPOSITORY");
    }

    @Test
    @DisplayName("Name ending in Dto → DTO layer")
    void layer_dtoByNamingConvention() {
        Optional<ParsedClass> result = parser.parse(file("CreateOrderDto.java", """
                package com.example;
                public class CreateOrderDto {
                    private String productId;
                    private int quantity;
                    public String getProductId() { return productId; }
                    public void setProductId(String productId) { this.productId = productId; }
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getLayer()).isEqualTo("DTO");
    }

    @Test
    @DisplayName("@Configuration → CONFIG layer")
    void layer_configurationAnnotation() {
        Optional<ParsedClass> result = parser.parse(file("AppConfig.java", """
                package com.example;
                @Configuration
                public class AppConfig {
                    @Bean public Object myBean() { return new Object(); }
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getLayer()).isEqualTo("CONFIG");
    }

    // ── Method parsing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Public methods are parsed with correct name and parameters")
    void methods_publicMethodsParsed() {
        Optional<ParsedClass> result = parser.parse(file("OrderService.java", """
                package com.example;
                @Service
                public class OrderService {
                    public Order createOrder(CreateOrderRequest req) { return null; }
                    public Order getOrder(Long id) { return null; }
                    private void internalHelper() {}
                }
                """));
        assertThat(result).isPresent();
        ParsedClass cls = result.get();
        assertThat(cls.getMethods()).hasSize(3);
        assertThat(cls.getMethods().stream().filter(ParsedMethod::isPublic).toList()).hasSize(2);
        assertThat(cls.getMethods()).extracting(ParsedMethod::getName)
                .contains("createOrder", "getOrder", "internalHelper");
    }

    @Test
    @DisplayName("Method annotations are captured")
    void methods_annotationsCaptured() {
        Optional<ParsedClass> result = parser.parse(file("OrderService.java", """
                package com.example;
                @Service
                public class OrderService {
                    @Transactional
                    @Cacheable("orders")
                    public Order getOrder(Long id) { return null; }
                }
                """));
        assertThat(result).isPresent();
        ParsedMethod method = result.get().getMethods().get(0);
        assertThat(method.getAnnotations()).contains("Transactional", "Cacheable");
    }

    @Test
    @DisplayName("Method body call expressions are extracted")
    void methods_calledMethodsExtracted() {
        Optional<ParsedClass> result = parser.parse(file("OrderService.java", """
                package com.example;
                @Service
                public class OrderService {
                    private OrderRepository repo;
                    public void createOrder() {
                        validate();
                        repo.save(new Order());
                        kafkaTemplate.send("topic", "msg");
                    }
                    private void validate() {}
                }
                """));
        assertThat(result).isPresent();
        ParsedMethod create = result.get().getMethods().stream()
                .filter(m -> m.getName().equals("createOrder")).findFirst().orElseThrow();
        assertThat(create.getCalledMethods()).contains("validate", "save", "send");
    }

    // ── Field parsing ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Injected fields are parsed with type and name")
    void fields_injectedFieldsParsed() {
        Optional<ParsedClass> result = parser.parse(file("OrderService.java", """
                package com.example;
                @Service
                public class OrderService {
                    @Autowired private OrderRepository orderRepository;
                    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getFields()).extracting(f -> f.getName())
                .contains("orderRepository", "kafkaTemplate");
        assertThat(result.get().getFields()).extracting(f -> f.getType())
                .anyMatch(t -> t.contains("KafkaTemplate"));
    }

    // ── Class metadata ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Implemented interfaces are captured")
    void metadata_implementedInterfaces() {
        Optional<ParsedClass> result = parser.parse(file("OrderServiceImpl.java", """
                package com.example;
                @Service
                public class OrderServiceImpl implements OrderService, Validatable {
                    public void createOrder() {}
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getImplementedInterfaces()).contains("OrderService", "Validatable");
    }

    @Test
    @DisplayName("Superclass is captured")
    void metadata_superClass() {
        Optional<ParsedClass> result = parser.parse(file("OrderNotFoundException.java", """
                package com.example;
                public class OrderNotFoundException extends RuntimeException {
                    public OrderNotFoundException(String msg) { super(msg); }
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getSuperClass()).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("Fully qualified name combines package and simple name")
    void metadata_fullyQualifiedName() {
        Optional<ParsedClass> result = parser.parse(file("OrderService.java", """
                package com.example.order;
                @Service
                public class OrderService {}
                """));
        assertThat(result).isPresent();
        assertThat(result.get().getFullyQualifiedName()).isEqualTo("com.example.order.OrderService");
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unparseable source returns empty Optional without throwing")
    void edgeCase_malformedSource_returnsEmpty() {
        Optional<ParsedClass> result = parser.parse(file("Broken.java", "this is not java {{{{"));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Empty file returns empty Optional")
    void edgeCase_emptyFile_returnsEmpty() {
        Optional<ParsedClass> result = parser.parse(file("Empty.java", ""));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Interface is parsed with isInterface=true")
    void edgeCase_interfaceParsed() {
        Optional<ParsedClass> result = parser.parse(file("OrderRepository.java", """
                package com.example;
                public interface OrderRepository {
                    Order findById(Long id);
                }
                """));
        assertThat(result).isPresent();
        assertThat(result.get().isInterface()).isTrue();
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private GitHubFile file(String path, String content) {
        return GitHubFile.builder().path(path).content(content).sha("sha").sizeBytes(content.length()).build();
    }
}
