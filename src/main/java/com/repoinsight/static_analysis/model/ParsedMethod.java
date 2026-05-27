package com.repoinsight.static_analysis.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ParsedMethod {
    String name;
    String returnType;
    List<String> parameterTypes;
    List<String> annotations;       // @GetMapping, @Transactional, @Cacheable …
    List<String> calledMethods;     // method call expressions inside the body
    List<String> calledClasses;     // types referenced in the body
    boolean isPublic;
    boolean isStatic;

    /**
     * HTTP verb extracted from @GetMapping / @PostMapping / @PutMapping /
     * @DeleteMapping / @PatchMapping / @RequestMapping annotations.
     * Null when this method is not a REST endpoint.
     */
    String httpMethod;   // "GET" | "POST" | "PUT" | "DELETE" | "PATCH" | null

    /**
     * URL path from the mapping annotation, e.g. {@code "/orders/{id}"}.
     * Does NOT include the class-level base path — combine with
     * {@link com.repoinsight.static_analysis.model.ParsedClass#getBaseHttpPath()}
     * to get the full path.  Null when not a REST endpoint.
     */
    String httpPath;

    public String signature() {
        return name + "(" + String.join(", ", parameterTypes) + ")";
    }

    /** True when this method is mapped to an HTTP endpoint. */
    public boolean isRestEndpoint() {
        return httpMethod != null && !httpMethod.isBlank();
    }
}
