package com.repoinsight.generator;

import com.repoinsight.analyzer.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Renders AnalysisResult into human-readable Markdown documents.
 */
@Component
@Slf4j
public class DocumentationGenerator {

    public String renderBusinessLogic(List<BusinessFlow> flows, String repoUrl) {
        StringBuilder md = new StringBuilder();
        md.append("# Business Logic Documentation\n\n");
        md.append("**Repository:** ").append(repoUrl).append("  \n");
        md.append("**Generated:** ").append(LocalDate.now()).append("\n\n");
        md.append("---\n\n");
        md.append("## Table of Contents\n\n");
        for (int i = 0; i < flows.size(); i++) {
            md.append("%d. [%s](#%s)\n".formatted(i + 1, flows.get(i).getName(),
                    anchor(flows.get(i).getName())));
        }
        md.append("\n---\n\n");

        for (BusinessFlow flow : flows) {
            md.append("## ").append(flow.getName()).append("\n\n");
            md.append("**Trigger:** `").append(flow.getTrigger()).append("`\n\n");
            md.append("### What It Does\n\n").append(flow.getDescription()).append("\n\n");

            md.append("### Step-by-Step Flow\n\n");
            for (int i = 0; i < flow.getSteps().size(); i++) {
                md.append("%d. %s\n".formatted(i + 1, flow.getSteps().get(i)));
            }

            md.append("\n### Business Rules & Invariants\n\n");
            flow.getInvariants().forEach(inv -> md.append("- ").append(inv).append("\n"));

            md.append("\n### Side Effects\n\n");
            flow.getSideEffects().forEach(se -> md.append("- ").append(se).append("\n"));
            md.append("\n---\n\n");
        }

        return md.toString();
    }

    public String renderMethodHierarchy(List<MethodCallNode> trees, String repoUrl) {
        StringBuilder md = new StringBuilder();
        md.append("# Method Call Hierarchy\n\n");
        md.append("**Repository:** ").append(repoUrl).append("  \n");
        md.append("**Generated:** ").append(LocalDate.now()).append("\n\n");
        md.append("---\n\n");

        for (MethodCallNode root : trees) {
            md.append("## Entry Point: `").append(root.getMethodSignature()).append("`\n\n");
            md.append("**Class:** `").append(root.getClassName()).append("`  \n");
            md.append("**Layer:** ").append(root.getLayer()).append("\n\n");
            md.append("```\n");
            renderNode(md, root, "");
            md.append("```\n\n---\n\n");
        }
        return md.toString();
    }

    public String renderIntegrations(List<IntegrationPoint> points, String repoUrl) {
        StringBuilder md = new StringBuilder();
        md.append("# Integration Topology\n\n");
        md.append("**Repository:** ").append(repoUrl).append("  \n");
        md.append("**Generated:** ").append(LocalDate.now()).append("\n\n");

        List<String> categories = List.of("DB", "KAFKA", "S3", "CACHE", "HTTP", "THIRD_PARTY");
        for (String cat : categories) {
            List<IntegrationPoint> byCategory = points.stream()
                    .filter(p -> cat.equals(p.getCategory())).toList();
            if (byCategory.isEmpty()) continue;

            md.append("## ").append(categoryLabel(cat)).append("\n\n");
            md.append("| Integration | Direction | Class | Method | Pattern |\n");
            md.append("|---|---|---|---|---|\n");
            for (IntegrationPoint ip : byCategory) {
                md.append("| %s | %s | `%s` | `%s` | `%s` |\n".formatted(
                        ip.getName(), ip.getDirection(), ip.getClassRef(),
                        ip.getMethodRef(), ip.getDetectionPattern()));
            }
            md.append("\n");
        }
        return md.toString();
    }

    private void renderNode(StringBuilder sb, MethodCallNode node, String prefix) {
        String integration = node.getIntegrationKind() != null
                && node.getIntegrationKind() != MethodCallNode.IntegrationKind.NONE
                ? "  [%s → %s]".formatted(node.getIntegrationKind(), node.getIntegrationTarget())
                : "";

        sb.append(prefix).append(node.getClassName()).append(".")
                .append(node.getMethodSignature()).append(integration).append("\n");

        if (node.getChildren() != null) {
            for (int i = 0; i < node.getChildren().size(); i++) {
                boolean last = i == node.getChildren().size() - 1;
                String childPrefix = prefix + (last ? "    " : "│   ");
                String connector = last ? "└── " : "├── ";
                sb.append(prefix).append(connector);
                renderNode(sb, node.getChildren().get(i), childPrefix);
            }
        }
    }

    private String anchor(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    private String categoryLabel(String cat) {
        return switch (cat) {
            case "DB" -> "Database (JDBC / JPA)";
            case "KAFKA" -> "Message Broker (Kafka)";
            case "S3" -> "Object Storage (S3)";
            case "CACHE" -> "Caching (Redis / Caffeine)";
            case "HTTP" -> "HTTP / REST Clients";
            case "THIRD_PARTY" -> "Third-party SDKs";
            default -> cat;
        };
    }
}
