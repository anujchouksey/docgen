package com.repoinsight.coverage.service;

import com.repoinsight.coverage.model.CoverageReport;
import com.repoinsight.coverage.model.CoverageStatus;
import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.static_analysis.JavaAstParser;
import com.repoinsight.static_analysis.TemplateGherkinGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticCoverageAnalyserTest {

    private StaticCoverageAnalyser analyser;

    @BeforeEach
    void setUp() {
        analyser = new StaticCoverageAnalyser(new JavaAstParser(), new TemplateGherkinGenerator());
    }

    // ── NOT_NEEDED classification ──────────────────────────────────────────

    @Test
    @DisplayName("DTO class (only getters/setters) → NOT_NEEDED")
    void notNeeded_dtoClass() {
        GitHubFile dto = devFile("CreateOrderDto.java", """
                package com.example;
                public class CreateOrderDto {
                    private String productId;
                    private int quantity;
                    public String getProductId() { return productId; }
                    public void setProductId(String p) { this.productId = p; }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int q) { this.quantity = q; }
                }
                """);

        CoverageReport report = analyse(List.of(dto), List.of());

        assertThat(report.getClasses()).hasSize(1);
        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.NOT_NEEDED);
    }

    @Test
    @DisplayName("@Configuration class → NOT_NEEDED")
    void notNeeded_configurationClass() {
        GitHubFile config = devFile("AppConfig.java", """
                package com.example;
                @Configuration
                public class AppConfig {
                    @Bean public Object myBean() { return new Object(); }
                }
                """);

        CoverageReport report = analyse(List.of(config), List.of());

        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.NOT_NEEDED);
    }

    @Test
    @DisplayName("RuntimeException subclass → NOT_NEEDED")
    void notNeeded_exceptionSubclass() {
        GitHubFile exc = devFile("OrderNotFoundException.java", """
                package com.example;
                public class OrderNotFoundException extends RuntimeException {
                    public OrderNotFoundException(String msg) { super(msg); }
                }
                """);

        CoverageReport report = analyse(List.of(exc), List.of());

        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.NOT_NEEDED);
    }

    // ── MISSED classification ──────────────────────────────────────────────

    @Test
    @DisplayName("Service with no matching QA files → MISSED")
    void missed_noMatchingQaFiles() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                    public String getOrder(Long id) { return null; }
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of(
                qaFile("UnrelatedTest.java", """
                        package com.example;
                        class UnrelatedTest {
                            void test() {}
                        }
                        """)
        ));

        assertThat(report.getClasses()).hasSize(1);
        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.MISSED);
        assertThat(report.getClasses().get(0).getMissedMethods()).isNotEmpty();
        assertThat(report.getClasses().get(0).getCoveredMethods()).isEmpty();
    }

    @Test
    @DisplayName("Service with no QA files at all → MISSED with suggested Gherkin")
    void missed_emptyQaRepo() {
        GitHubFile svc = devFile("PaymentService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class PaymentService {
                    public void charge(Long amount) {}
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of());

        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.MISSED);
        assertThat(report.getClasses().get(0).getSuggestedGherkin()).isNotNull().isNotBlank();
    }

    // ── COVERED classification ─────────────────────────────────────────────

    @Test
    @DisplayName("All methods mentioned in QA file with edge cases → COVERED")
    void covered_allMethodsAndEdgeCases() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                }
                """);

        GitHubFile testFile = qaFile("OrderServiceTest.java", """
                package com.example;
                import com.example.OrderService;
                class OrderServiceTest {
                    OrderService orderService;
                    void testCreateOrder() { orderService.createOrder("req"); }
                    void testCreateOrder_notFound() { /* 404 error case */ }
                    void testCreateOrder_exception() {}
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of(testFile));

        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.COVERED);
    }

    // ── PARTIAL classification ─────────────────────────────────────────────

    @Test
    @DisplayName("Some methods covered, some missed → PARTIAL")
    void partial_someMethodsMissed() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                    public void deleteOrder(Long id) {}
                }
                """);

        GitHubFile testFile = qaFile("OrderServiceTest.java", """
                package com.example;
                import com.example.OrderService;
                class OrderServiceTest {
                    OrderService orderService;
                    void testCreateOrder() {
                        orderService.createOrder("req");
                        // 404 error case
                    }
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of(testFile));

        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(report.getClasses().get(0).getCoveredMethods()).isNotEmpty();
        assertThat(report.getClasses().get(0).getMissedMethods()).isNotEmpty();
    }

    @Test
    @DisplayName("All methods covered but no edge cases → PARTIAL (happy path only)")
    void partial_noEdgeCaseScenarios() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                }
                """);

        // Test references the method but has no failure scenario keywords
        GitHubFile testFile = qaFile("OrderServiceTest.java", """
                package com.example;
                import com.example.OrderService;
                class OrderServiceTest {
                    OrderService orderService;
                    void testCreateOrder() {
                        orderService.createOrder("req");
                    }
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of(testFile));

        assertThat(report.getClasses().get(0).getStatus()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(report.getClasses().get(0).getMissingScenarios())
                .anyMatch(s -> s.toLowerCase().contains("edge case") || s.toLowerCase().contains("happy path"));
    }

    // ── QA file type recognition ──────────────────────────────────────────

    @Test
    @DisplayName("*Test.java naming convention → matched as QA file")
    void qaIndex_testFilenameConvention() {
        GitHubFile svc = devFile("InventoryService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class InventoryService {
                    public void reduceStock(Long id) {}
                }
                """);

        GitHubFile testFile = qaFile("InventoryServiceTest.java", """
                package com.example;
                import com.example.InventoryService;
                class InventoryServiceTest {
                    InventoryService inventoryService;
                    void testReduceStock_notFound() { /* 404 */ }
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of(testFile));

        // Should find InventoryServiceTest as a match for InventoryService
        assertThat(report.getClasses().get(0).getRelevantQaFiles())
                .anyMatch(f -> f.contains("InventoryServiceTest"));
    }

    @Test
    @DisplayName(".feature file referencing class name → matched via regex scan")
    void qaIndex_featureFileMatched() {
        GitHubFile svc = devFile("PaymentService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class PaymentService {
                    public void processPayment(Long amount) {}
                }
                """);

        GitHubFile featureFile = qaFile("payment.feature", """
                Feature: PaymentService
                  Scenario: Process payment successfully
                    Given a valid payment request
                    When processPayment is called
                    Then the payment succeeds
                  Scenario: payment fails with 500 error
                    Given an invalid payment
                    When processPayment is called
                    Then an exception is thrown
                """);

        CoverageReport report = analyse(List.of(svc), List.of(featureFile));

        assertThat(report.getClasses().get(0).getRelevantQaFiles()).isNotEmpty();
    }

    // ── Coverage score formula ────────────────────────────────────────────

    @Test
    @DisplayName("All covered → score is 100")
    void score_allCovered() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                }
                """);
        GitHubFile testFile = qaFile("OrderServiceTest.java", """
                package com.example;
                import com.example.OrderService;
                class OrderServiceTest {
                    OrderService orderService;
                    void testCreateOrder() { orderService.createOrder("req"); }
                    void testCreateOrder_notFound() { /* 404 error case */ }
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of(testFile));

        assertThat(report.getSummary().getCoverageScore()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("All missed → score is 0")
    void score_allMissed() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of());

        assertThat(report.getSummary().getCoverageScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("One covered, one missed (from 2 testable classes) → score is 50")
    void score_halfCovered() {
        GitHubFile svc1 = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                }
                """);
        GitHubFile svc2 = devFile("PaymentService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class PaymentService {
                    public void processPayment(Long amt) {}
                }
                """);
        GitHubFile testFile = qaFile("OrderServiceTest.java", """
                package com.example;
                import com.example.OrderService;
                class OrderServiceTest {
                    OrderService orderService;
                    void testCreateOrder() { orderService.createOrder("x"); }
                    void testCreateOrder_404() { /* error */ }
                }
                """);

        CoverageReport report = analyse(List.of(svc1, svc2), List.of(testFile));

        // 1 covered (score 1.0), 1 missed (score 0.0) → 50%
        assertThat(report.getSummary().getCoverageScore()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("NOT_NEEDED classes excluded from testable count in score")
    void score_notNeededExcluded() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                }
                """);
        GitHubFile dto = devFile("CreateOrderDto.java", """
                package com.example;
                public class CreateOrderDto {
                    private String name;
                    public String getName() { return name; }
                    public void setName(String n) { this.name = n; }
                }
                """);
        GitHubFile testFile = qaFile("OrderServiceTest.java", """
                package com.example;
                import com.example.OrderService;
                class OrderServiceTest {
                    OrderService orderService;
                    void testCreateOrder() { orderService.createOrder("req"); }
                    void testCreateOrder_error() { /* 404 exception */ }
                }
                """);

        CoverageReport report = analyse(List.of(svc, dto), List.of(testFile));

        // DTO is NOT_NEEDED → only 1 testable class (OrderService) which is COVERED → 100%
        assertThat(report.getSummary().getNotNeeded()).isEqualTo(1);
        assertThat(report.getSummary().getCoverageScore()).isEqualTo(100.0);
    }

    // ── Missing scenario suggestions ──────────────────────────────────────

    @Test
    @DisplayName("CONTROLLER layer missed → 401, 403, 400 scenarios suggested")
    void missingScenarios_controllerLayer() {
        GitHubFile ctrl = devFile("OrderController.java", """
                package com.example;
                @RestController
                public class OrderController {
                    public String getOrder(Long id) { return null; }
                }
                """);

        CoverageReport report = analyse(List.of(ctrl), List.of());

        List<String> scenarios = report.getClasses().get(0).getMissingScenarios();
        assertThat(scenarios).anyMatch(s -> s.contains("401") || s.contains("403") || s.contains("400"));
    }

    @Test
    @DisplayName("get* method missed → 404 scenario suggested")
    void missingScenarios_getMethod404() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public String getOrder(Long id) { return null; }
                }
                """);

        CoverageReport report = analyse(List.of(svc), List.of());

        List<String> scenarios = report.getClasses().get(0).getMissingScenarios();
        assertThat(scenarios).anyMatch(s -> s.contains("not found") || s.contains("404"));
    }

    // ── Layer focus filter ────────────────────────────────────────────────

    @Test
    @DisplayName("layerFocus=SERVICE → only SERVICE classes analysed")
    void layerFilter_serviceOnly() {
        GitHubFile svc = devFile("OrderService.java", """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(String req) {}
                }
                """);
        GitHubFile ctrl = devFile("OrderController.java", """
                package com.example;
                @RestController
                public class OrderController {
                    public String getOrder(Long id) { return null; }
                }
                """);

        CoverageReport report = analyseWithFocus(List.of(svc, ctrl), List.of(), "SERVICE");

        assertThat(report.getClasses()).hasSize(1);
        assertThat(report.getClasses().get(0).getDevClass()).isEqualTo("OrderService");
    }

    // ── Report metadata ────────────────────────────────────────────────────

    @Test
    @DisplayName("Report contains jobId, devRepoUrl, qaRepoUrl, generatedAt")
    void reportMetadata() {
        CoverageReport report = analyse(List.of(), List.of());

        assertThat(report.getJobId()).isEqualTo("test-job");
        assertThat(report.getDevRepoUrl()).contains("dev");
        assertThat(report.getQaRepoUrl()).contains("qa");
        assertThat(report.getGeneratedAt()).isNotNull();
        assertThat(report.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Executive summary contains score and risk level")
    void executiveSummary_containsScoreAndRisk() {
        CoverageReport report = analyse(List.of(), List.of());

        assertThat(report.getExecutiveSummary()).isNotBlank();
        assertThat(report.getExecutiveSummary()).containsPattern("\\d+\\.\\d+%");
        assertThat(report.getExecutiveSummary()).matches(".*(?i)(high|moderate|low).*");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private CoverageReport analyse(List<GitHubFile> devFiles, List<GitHubFile> qaFiles) {
        return analyser.analyse(devFiles, qaFiles,
                "https://github.com/dev/repo", "https://github.com/qa/repo",
                "main", "main", "test-job", "ALL");
    }

    private CoverageReport analyseWithFocus(List<GitHubFile> devFiles, List<GitHubFile> qaFiles, String focus) {
        return analyser.analyse(devFiles, qaFiles,
                "https://github.com/dev/repo", "https://github.com/qa/repo",
                "main", "main", "test-job", focus);
    }

    private GitHubFile devFile(String path, String content) {
        return GitHubFile.builder().path(path).content(content).sha("dev-sha").sizeBytes(content.length()).build();
    }

    private GitHubFile qaFile(String path, String content) {
        return GitHubFile.builder().path(path).content(content).sha("qa-sha").sizeBytes(content.length()).build();
    }
}
