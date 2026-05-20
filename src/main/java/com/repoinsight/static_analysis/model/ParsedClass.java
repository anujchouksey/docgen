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
}
