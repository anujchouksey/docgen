package com.repoinsight.static_analysis;

import com.repoinsight.analyzer.model.BusinessFlow;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedMethod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Derives BusinessFlow objects from the AST — no AI.
 * Entry points: controller methods, @Scheduled, @KafkaListener, @EventListener.
 */
@Component
public class StaticBusinessLogicExtractor {

    public List<BusinessFlow> extract(List<ParsedClass> classes) {
        List<BusinessFlow> flows = new ArrayList<>();
        for (ParsedClass cls : classes) {
            flows.addAll(extractFromClass(cls, classes));
        }
        return flows;
    }

    private List<BusinessFlow> extractFromClass(ParsedClass cls, List<ParsedClass> allClasses) {
        List<BusinessFlow> flows = new ArrayList<>();

        for (ParsedMethod method : cls.getMethods()) {
            if (!method.isPublic() || method.isStatic()) continue;

            String trigger = deriveTrigger(cls, method);
            if (trigger == null) continue;  // not an entry point

            String name = toFlowName(cls.getSimpleName(), method.getName());
            String description = buildDescription(cls, method, allClasses);
            List<String> steps = buildSteps(method, allClasses);
            List<String> invariants = deriveInvariants(method, cls);
            List<String> sideEffects = deriveSideEffects(method, allClasses);

            flows.add(BusinessFlow.builder()
                    .name(name)
                    .trigger(trigger)
                    .description(description)
                    .steps(steps)
                    .invariants(invariants)
                    .sideEffects(sideEffects)
                    .build());
        }
        return flows;
    }

    // ── Trigger detection ──────────────────────────────────────────────────

    private String deriveTrigger(ParsedClass cls, ParsedMethod method) {
        // REST endpoint annotations
        for (String ann : method.getAnnotations()) {
            if (ann.startsWith("GetMapping"))    return "GET " + extractPath(method, "GET");
            if (ann.startsWith("PostMapping"))   return "POST " + extractPath(method, "POST");
            if (ann.startsWith("PutMapping"))    return "PUT " + extractPath(method, "PUT");
            if (ann.startsWith("DeleteMapping")) return "DELETE " + extractPath(method, "DELETE");
            if (ann.startsWith("PatchMapping"))  return "PATCH " + extractPath(method, "PATCH");
            if (ann.startsWith("RequestMapping")) return "HTTP " + extractPath(method, "HTTP");
        }
        // Scheduled
        if (method.getAnnotations().contains("Scheduled"))
            return "Scheduled (cron/fixed-rate)";
        // Kafka consumer
        if (method.getAnnotations().contains("KafkaListener"))
            return "Kafka consumer: " + cls.getSimpleName();
        // Event listener
        if (method.getAnnotations().contains("EventListener") || method.getAnnotations().contains("TransactionalEventListener"))
            return "Spring event: " + method.getParameterTypes().stream().findFirst().orElse("ApplicationEvent");
        // Controller layer — every public method is an implied entry point
        if ("CONTROLLER".equals(cls.getLayer()))
            return "HTTP " + cls.getSimpleName().replace("Controller", "") + "/" + method.getName();

        return null; // not an entry point — skip
    }

    private String extractPath(ParsedMethod method, String httpMethod) {
        // In static mode we can't resolve annotation values without full classpath — return a placeholder
        return "/<" + method.getName() + "-path>";
    }

    // ── Step derivation ────────────────────────────────────────────────────

    private List<String> buildSteps(ParsedMethod method, List<ParsedClass> allClasses) {
        List<String> steps = new ArrayList<>();
        // Map each called method to a human-readable step
        for (String called : method.getCalledMethods()) {
            String step = methodNameToStep(called);
            if (step != null && !steps.contains(step)) steps.add(step);
        }
        // Fallback if body is empty / not parsed
        if (steps.isEmpty()) steps.add("Execute " + method.getName() + " logic");
        return steps;
    }

    private String methodNameToStep(String methodName) {
        return switch (true) {
            case true when methodName.startsWith("validate") || methodName.startsWith("check") -> "Validate input: " + methodName;
            case true when methodName.startsWith("save") || methodName.startsWith("persist") -> "Persist entity to database";
            case true when methodName.startsWith("find") || methodName.startsWith("get") || methodName.startsWith("load") -> "Fetch data: " + methodName;
            case true when methodName.startsWith("delete") || methodName.startsWith("remove") -> "Delete/remove record";
            case true when methodName.startsWith("send") || methodName.startsWith("publish") || methodName.startsWith("emit") -> "Publish event / message";
            case true when methodName.startsWith("notify") || methodName.startsWith("email") || methodName.startsWith("sms") -> "Send notification";
            case true when methodName.startsWith("charge") || methodName.startsWith("pay") || methodName.startsWith("refund") -> "Process payment: " + methodName;
            case true when methodName.startsWith("upload") || methodName.startsWith("store") -> "Upload to object storage";
            case true when methodName.startsWith("cache") -> "Cache result";
            case true when methodName.startsWith("map") || methodName.startsWith("convert") || methodName.startsWith("transform") -> "Map/transform data";
            default -> null;
        };
    }

    // ── Business description ───────────────────────────────────────────────

    private String buildDescription(ParsedClass cls, ParsedMethod method, List<ParsedClass> allClasses) {
        StringBuilder desc = new StringBuilder();
        desc.append(humanise(cls.getSimpleName())).append(" exposes ");
        desc.append(humanise(method.getName())).append(".");

        if (!method.getParameterTypes().isEmpty()) {
            desc.append(" Accepts: ").append(String.join(", ", method.getParameterTypes())).append(".");
        }
        if (!"void".equals(method.getReturnType())) {
            desc.append(" Returns: ").append(method.getReturnType()).append(".");
        }
        // Transactional note
        if (method.getAnnotations().contains("Transactional")) {
            desc.append(" Wrapped in a database transaction.");
        }
        if (method.getAnnotations().contains("PreAuthorize") || method.getAnnotations().contains("Secured")) {
            desc.append(" Access is restricted by role-based authorization.");
        }
        return desc.toString();
    }

    // ── Invariants ─────────────────────────────────────────────────────────

    private List<String> deriveInvariants(ParsedMethod method, ParsedClass cls) {
        List<String> invariants = new ArrayList<>();
        if (method.getAnnotations().contains("NotNull") || method.getAnnotations().contains("Valid")) {
            invariants.add("Input must not be null / must be valid");
        }
        if (method.getAnnotations().contains("PreAuthorize") || method.getAnnotations().contains("Secured")) {
            invariants.add("Caller must have the required role/permission");
        }
        if (method.getAnnotations().contains("Transactional")) {
            invariants.add("All writes within this method are atomic");
        }
        if ("REPOSITORY".equals(cls.getLayer())) {
            invariants.add("Entity must satisfy database constraints before persistence");
        }
        return invariants;
    }

    // ── Side effects ───────────────────────────────────────────────────────

    private List<String> deriveSideEffects(ParsedMethod method, List<ParsedClass> allClasses) {
        List<String> effects = new ArrayList<>();
        for (String called : method.getCalledMethods()) {
            if (called.startsWith("send") || called.startsWith("publish") || called.startsWith("emit"))
                effects.add("Message / event published: " + called + "()");
            if (called.startsWith("notify") || called.startsWith("email") || called.startsWith("sms"))
                effects.add("Notification sent: " + called + "()");
            if (called.startsWith("evict") || called.contains("evict"))
                effects.add("Cache entry evicted");
        }
        for (String refClass : method.getCalledClasses()) {
            if (refClass.contains("KafkaTemplate"))
                effects.add("Kafka message produced");
            if (refClass.contains("S3") || refClass.contains("AmazonS3"))
                effects.add("S3 object written");
        }
        return effects.stream().distinct().collect(Collectors.toList());
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private String toFlowName(String className, String methodName) {
        return humanisePascal(className) + "_" + humanisePascal(methodName) + "Flow";
    }

    private String humanisePascal(String name) {
        return name.replaceAll("([A-Z])", " $1").trim().replace(" ", "_");
    }

    private String humanise(String name) {
        return name.replaceAll("([A-Z])", " $1").trim().toLowerCase();
    }
}
