package com.repoinsight.static_analysis;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedField;
import com.repoinsight.static_analysis.model.ParsedMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticBusinessLogicExtractorTest {

    private StaticBusinessLogicExtractor extractor;

    @BeforeEach
    void setUp() { extractor = new StaticBusinessLogicExtractor(); }

    // ── Entry-point detection ─────────────────────────────────────────────

    @Test
    @DisplayName("@PostMapping method → flow with POST trigger extracted")
    void trigger_postMapping() {
        ParsedClass cls = controllerClass("OrderController",
                List.of(endpointMethod("createOrder", List.of("PostMapping"), List.of("CreateOrderRequest"))));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).getTrigger()).startsWith("POST");
    }

    @Test
    @DisplayName("@GetMapping method → flow with GET trigger extracted")
    void trigger_getMapping() {
        ParsedClass cls = controllerClass("OrderController",
                List.of(endpointMethod("getOrder", List.of("GetMapping"), List.of("Long"))));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).getTrigger()).startsWith("GET");
    }

    @Test
    @DisplayName("@DeleteMapping method → flow with DELETE trigger extracted")
    void trigger_deleteMapping() {
        ParsedClass cls = controllerClass("OrderController",
                List.of(endpointMethod("cancelOrder", List.of("DeleteMapping"), List.of("Long"))));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).isNotEmpty();
        assertThat(flows.get(0).getTrigger()).startsWith("DELETE");
    }

    @Test
    @DisplayName("@Scheduled method → Scheduled trigger")
    void trigger_scheduled() {
        ParsedMethod scheduledMethod = publicMethod("cleanupExpiredOrders", List.of("Scheduled"), List.of());
        ParsedClass cls = serviceClass("OrderCleanupService", List.of(scheduledMethod));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).anyMatch(f -> f.getTrigger().contains("Scheduled"));
    }

    @Test
    @DisplayName("@KafkaListener method → Kafka consumer trigger")
    void trigger_kafkaListener() {
        ParsedMethod listener = publicMethod("onOrderEvent", List.of("KafkaListener"), List.of("OrderEvent"));
        ParsedClass cls = serviceClass("OrderEventConsumer", List.of(listener));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).anyMatch(f -> f.getTrigger().contains("Kafka consumer"));
    }

    @Test
    @DisplayName("@EventListener method → Spring event trigger")
    void trigger_eventListener() {
        ParsedMethod listener = publicMethod("onUserRegistered", List.of("EventListener"), List.of("UserRegisteredEvent"));
        ParsedClass cls = serviceClass("WelcomeEmailService", List.of(listener));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).anyMatch(f -> f.getTrigger().contains("Spring event") && f.getTrigger().contains("UserRegisteredEvent"));
    }

    @Test
    @DisplayName("CONTROLLER layer public method without HTTP annotation → implied HTTP trigger")
    void trigger_controllerLayerImplied() {
        ParsedMethod m = publicMethod("listOrders", List.of(), List.of());
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).anyMatch(f -> f.getTrigger().startsWith("HTTP "));
    }

    @Test
    @DisplayName("Private method on SERVICE layer → not an entry point, no flow")
    void trigger_privateMethodSkipped() {
        ParsedMethod priv = ParsedMethod.builder()
                .name("internalHelper")
                .returnType("void")
                .parameterTypes(List.of())
                .annotations(List.of())
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(false)
                .isStatic(false)
                .build();
        ParsedClass cls = serviceClass("OrderService", List.of(priv));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).isEmpty();
    }

    @Test
    @DisplayName("Static method → not an entry point, no flow")
    void trigger_staticMethodSkipped() {
        ParsedMethod staticM = ParsedMethod.builder()
                .name("util")
                .returnType("String")
                .parameterTypes(List.of())
                .annotations(List.of("GetMapping"))
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(true)
                .build();
        ParsedClass cls = controllerClass("OrderController", List.of(staticM));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).isEmpty();
    }

    // ── Step derivation ───────────────────────────────────────────────────

    @Test
    @DisplayName("validate call → 'Validate input' step")
    void steps_validateCallMapped() {
        ParsedMethod m = methodWithCalls("createOrder", List.of("PostMapping"),
                List.of("CreateOrderRequest"), List.of("validate", "save"));
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).isNotEmpty();
        assertThat(flows.get(0).getSteps()).anyMatch(s -> s.contains("Validate input"));
    }

    @Test
    @DisplayName("save call → 'Persist entity to database' step")
    void steps_saveCallMapped() {
        ParsedMethod m = methodWithCalls("createOrder", List.of("PostMapping"),
                List.of("CreateOrderRequest"), List.of("save"));
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getSteps()).anyMatch(s -> s.contains("Persist entity to database"));
    }

    @Test
    @DisplayName("send call → 'Publish event / message' step")
    void steps_sendCallMapped() {
        ParsedMethod m = methodWithCalls("placeOrder", List.of("PostMapping"),
                List.of(), List.of("send"));
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getSteps()).anyMatch(s -> s.contains("Publish event"));
    }

    @Test
    @DisplayName("charge call → 'Process payment' step")
    void steps_chargeCallMapped() {
        ParsedMethod m = methodWithCalls("processPayment", List.of("PostMapping"),
                List.of(), List.of("chargeCard"));
        ParsedClass cls = controllerClass("PaymentController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getSteps()).anyMatch(s -> s.contains("Process payment"));
    }

    @Test
    @DisplayName("Method with no recognisable calls → fallback 'Execute X logic' step")
    void steps_fallbackWhenNoKnownCalls() {
        ParsedMethod m = methodWithCalls("process", List.of("PostMapping"),
                List.of(), List.of("doSomethingUnrecognised"));
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getSteps()).anyMatch(s -> s.contains("Execute") && s.contains("process"));
    }

    // ── Invariants ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("@Transactional annotation → 'All writes are atomic' invariant")
    void invariants_transactional() {
        ParsedMethod m = ParsedMethod.builder()
                .name("createOrder")
                .returnType("Order")
                .parameterTypes(List.of("CreateOrderRequest"))
                .annotations(List.of("PostMapping", "Transactional"))
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getInvariants()).anyMatch(i -> i.contains("atomic"));
    }

    @Test
    @DisplayName("@PreAuthorize annotation → role/permission invariant")
    void invariants_preAuthorize() {
        ParsedMethod m = ParsedMethod.builder()
                .name("adminAction")
                .returnType("void")
                .parameterTypes(List.of())
                .annotations(List.of("PostMapping", "PreAuthorize"))
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();
        ParsedClass cls = controllerClass("AdminController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getInvariants()).anyMatch(i -> i.toLowerCase().contains("role"));
    }

    // ── Side effects ──────────────────────────────────────────────────────

    @Test
    @DisplayName("send() call in body → side effect 'Message / event published'")
    void sideEffects_sendPublished() {
        ParsedMethod m = methodWithCalls("placeOrder", List.of("PostMapping"),
                List.of(), List.of("sendOrderEvent"));
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getSideEffects()).anyMatch(e -> e.contains("published") || e.contains("event"));
    }

    @Test
    @DisplayName("notify() call in body → side effect 'Notification sent'")
    void sideEffects_notificationSent() {
        ParsedMethod m = methodWithCalls("createUser", List.of("PostMapping"),
                List.of(), List.of("notifyUser"));
        ParsedClass cls = controllerClass("UserController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getSideEffects()).anyMatch(e -> e.contains("Notification sent"));
    }

    // ── Flow naming ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Flow name includes class name and method name")
    void flowName_includesClassAndMethod() {
        ParsedMethod m = endpointMethod("createOrder", List.of("PostMapping"), List.of("CreateOrderRequest"));
        ParsedClass cls = controllerClass("OrderController", List.of(m));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows.get(0).getName()).contains("Order").contains("create");
    }

    // ── Multiple flows ────────────────────────────────────────────────────

    @Test
    @DisplayName("Multiple entry points → multiple flows")
    void multipleFlows() {
        ParsedClass cls = controllerClass("OrderController", List.of(
                endpointMethod("createOrder", List.of("PostMapping"), List.of("CreateOrderRequest")),
                endpointMethod("getOrder", List.of("GetMapping"), List.of("Long")),
                endpointMethod("cancelOrder", List.of("DeleteMapping"), List.of("Long"))
        ));

        List<BusinessFlow> flows = extractor.extract(List.of(cls));

        assertThat(flows).hasSize(3);
        assertThat(flows).extracting(BusinessFlow::getTrigger)
                .anyMatch(t -> t.startsWith("POST"))
                .anyMatch(t -> t.startsWith("GET"))
                .anyMatch(t -> t.startsWith("DELETE"));
    }

    @Test
    @DisplayName("Empty class list → no flows")
    void emptyInput_noFlows() {
        assertThat(extractor.extract(List.of())).isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ParsedClass controllerClass(String name, List<ParsedMethod> methods) {
        return ParsedClass.builder()
                .simpleName(name)
                .layer("CONTROLLER")
                .classAnnotations(List.of("RestController"))
                .implementedInterfaces(List.of())
                .superClass(null)
                .fields(List.of())
                .methods(methods)
                .isInterface(false)
                .isAbstract(false)
                .isEnum(false)
                .build();
    }

    private ParsedClass serviceClass(String name, List<ParsedMethod> methods) {
        return ParsedClass.builder()
                .simpleName(name)
                .layer("SERVICE")
                .classAnnotations(List.of("Service"))
                .implementedInterfaces(List.of())
                .superClass(null)
                .fields(List.of())
                .methods(methods)
                .isInterface(false)
                .isAbstract(false)
                .isEnum(false)
                .build();
    }

    private ParsedMethod endpointMethod(String name, List<String> annotations, List<String> params) {
        return ParsedMethod.builder()
                .name(name)
                .returnType("ResponseEntity")
                .parameterTypes(params)
                .annotations(annotations)
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();
    }

    private ParsedMethod publicMethod(String name, List<String> annotations, List<String> params) {
        return ParsedMethod.builder()
                .name(name)
                .returnType("void")
                .parameterTypes(params)
                .annotations(annotations)
                .calledMethods(List.of())
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();
    }

    private ParsedMethod methodWithCalls(String name, List<String> annotations,
                                         List<String> params, List<String> calledMethods) {
        return ParsedMethod.builder()
                .name(name)
                .returnType("void")
                .parameterTypes(params)
                .annotations(annotations)
                .calledMethods(calledMethods)
                .calledClasses(List.of())
                .isPublic(true)
                .isStatic(false)
                .build();
    }
}
