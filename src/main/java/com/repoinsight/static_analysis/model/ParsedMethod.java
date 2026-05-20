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

    public String signature() {
        return name + "(" + String.join(", ", parameterTypes) + ")";
    }
}
