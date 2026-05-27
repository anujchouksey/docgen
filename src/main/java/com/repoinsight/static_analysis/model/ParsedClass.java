package com.repoinsight.static_analysis.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ParsedClass {
    String simpleName;
    String fullyQualifiedName;
    String packageName;
    String filePath;                 // repo-relative path
    String layer;                    // SERVICE | CONTROLLER | REPOSITORY | COMPONENT | ENTITY | DTO | CONFIG | CLIENT | OTHER
    List<String> classAnnotations;   // @Service, @RestController, @Repository …
    List<String> implementedInterfaces;
    String superClass;
    List<ParsedField> fields;
    List<ParsedMethod> methods;
    boolean isInterface;
    boolean isAbstract;
    boolean isEnum;

    /**
     * Base HTTP path from a class-level {@code @RequestMapping} annotation,
     * e.g. {@code "/api/orders"}.  Null when no class-level mapping is present.
     * Combine with each method's {@code httpPath} for the full endpoint path.
     */
    String baseHttpPath;
}
