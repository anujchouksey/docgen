package com.repoinsight.static_analysis;

import com.repoinsight.analyzer.model.MethodCallNode;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedMethod;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds method call trees from the parsed AST.
 * Works within the repo boundary — external calls are shown as leaf nodes
 * with an integration kind tag derived from field-type heuristics.
 */
@Component
public class StaticMethodHierarchyBuilder {

    public List<MethodCallNode> buildTrees(List<ParsedClass> classes, int maxDepth) {
        Map<String, ParsedClass> byName = new HashMap<>();
        for (ParsedClass c : classes) byName.put(c.getSimpleName(), c);

        List<MethodCallNode> roots = new ArrayList<>();
        for (ParsedClass cls : classes) {
            if (!isEntryPoint(cls)) continue;
            for (ParsedMethod method : cls.getMethods()) {
                if (!method.isPublic() || method.isStatic()) continue;
                MethodCallNode tree = buildNode(cls, method, byName, 0, maxDepth, new HashSet<>());
                if (tree != null) roots.add(tree);
            }
        }
        return roots;
    }

    private MethodCallNode buildNode(ParsedClass cls, ParsedMethod method,
                                     Map<String, ParsedClass> byName,
                                     int depth, int maxDepth, Set<String> visited) {
        String nodeId = cls.getSimpleName() + "." + method.getName();
        if (depth > maxDepth || visited.contains(nodeId)) return null;
        visited.add(nodeId);

        MethodCallNode.IntegrationKind integration = detectIntegration(cls, method);
        String integrationTarget = deriveTarget(cls, method, integration);

        List<MethodCallNode> children = new ArrayList<>();
        if (depth < maxDepth) {
            for (String calledClass : method.getCalledClasses()) {
                ParsedClass callee = byName.get(calledClass);
                if (callee == null) continue;
                for (String calledMethodName : method.getCalledMethods()) {
                    callee.getMethods().stream()
                            .filter(m -> m.getName().equals(calledMethodName))
                            .findFirst()
                            .map(m -> buildNode(callee, m, byName, depth + 1, maxDepth, new HashSet<>(visited)))
                            .ifPresent(children::add);
                }
            }
        }

        return MethodCallNode.builder()
                .className(cls.getSimpleName())
                .methodSignature(method.signature())
                .layer(cls.getLayer())
                .integrationKind(integration)
                .integrationTarget(integrationTarget)
                .children(children)
                .depth(depth)
                .build();
    }

    private boolean isEntryPoint(ParsedClass cls) {
        return "CONTROLLER".equals(cls.getLayer())
                || cls.getMethods().stream().anyMatch(m ->
                        m.getAnnotations().contains("KafkaListener")
                        || m.getAnnotations().contains("Scheduled")
                        || m.getAnnotations().contains("EventListener"));
    }

    private MethodCallNode.IntegrationKind detectIntegration(ParsedClass cls, ParsedMethod method) {
        // Leaf node integrations based on class layer + called methods
        if ("REPOSITORY".equals(cls.getLayer())
                || cls.getImplementedInterfaces().stream().anyMatch(i -> i.contains("Repository")))
            return MethodCallNode.IntegrationKind.DB;

        if (method.getAnnotations().contains("KafkaListener"))
            return MethodCallNode.IntegrationKind.KAFKA_CONSUMER;

        if (method.getCalledClasses().stream().anyMatch(c -> c.contains("KafkaTemplate") || c.contains("KafkaProducer")))
            return MethodCallNode.IntegrationKind.KAFKA_PRODUCER;

        if (method.getCalledClasses().stream().anyMatch(c -> c.contains("S3") || c.contains("AmazonS3")))
            return MethodCallNode.IntegrationKind.S3;

        if (method.getAnnotations().stream().anyMatch(a -> a.startsWith("Cacheable") || a.startsWith("CachePut") || a.startsWith("CacheEvict")))
            return MethodCallNode.IntegrationKind.CACHE;

        if ("CLIENT".equals(cls.getLayer())
                || method.getCalledClasses().stream().anyMatch(c -> c.contains("RestTemplate") || c.contains("WebClient") || c.contains("HttpClient")))
            return MethodCallNode.IntegrationKind.HTTP;

        return MethodCallNode.IntegrationKind.NONE;
    }

    private String deriveTarget(ParsedClass cls, ParsedMethod method, MethodCallNode.IntegrationKind kind) {
        return switch (kind) {
            case DB -> "Database via " + cls.getSimpleName();
            case KAFKA_PRODUCER -> "Kafka (producer)";
            case KAFKA_CONSUMER -> "Kafka (consumer)";
            case S3 -> "S3 Object Storage";
            case CACHE -> "Cache";
            case HTTP -> cls.getSimpleName().replace("Client", "").replace("Gateway", "") + " API";
            default -> null;
        };
    }
}
