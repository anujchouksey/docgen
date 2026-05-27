package com.repoinsight.static_analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.repoinsight.github.model.GitHubFile;
import com.repoinsight.static_analysis.model.ParsedClass;
import com.repoinsight.static_analysis.model.ParsedField;
import com.repoinsight.static_analysis.model.ParsedMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * Converts raw Java source (from GitHub) into ParsedClass domain objects
 * using the JavaParser AST library. No AI required.
 */
@Component
@Slf4j
public class JavaAstParser {

    private final JavaParser parser = new JavaParser();

    public Optional<ParsedClass> parse(GitHubFile file) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file.getContent());
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.debug("JavaParser could not parse {}: {}", file.getPath(), result.getProblems());
                return Optional.empty();
            }
            CompilationUnit cu = result.getResult().get();
            return cu.getPrimaryType()
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration || t instanceof EnumDeclaration)
                    .map(t -> buildParsedClass(cu, t, file.getPath()));
        } catch (Exception e) {
            log.warn("Parse error for {}: {}", file.getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    // HTTP verb → annotation name mapping
    private static final Map<String, String> MAPPING_ANNOTATIONS = Map.of(
            "GetMapping",    "GET",
            "PostMapping",   "POST",
            "PutMapping",    "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping",  "PATCH"
    );

    private ParsedClass buildParsedClass(CompilationUnit cu, TypeDeclaration<?> type, String filePath) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getName().asString()).orElse("");
        String name = type.getNameAsString();
        boolean isInterface = type instanceof ClassOrInterfaceDeclaration cid && cid.isInterface();
        boolean isAbstract  = type instanceof ClassOrInterfaceDeclaration cid && cid.isAbstract();
        boolean isEnum      = type instanceof EnumDeclaration;

        List<String> classAnnotations = type.getAnnotations().stream()
                .map(a -> a.getNameAsString()).toList();

        List<String> interfaces = List.of();
        String superClass = null;
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            interfaces = cid.getImplementedTypes().stream()
                    .map(t -> t.getNameAsString()).toList();
            superClass = cid.getExtendedTypes().isEmpty() ? null
                    : cid.getExtendedTypes().get(0).getNameAsString();
        }

        // Extract class-level base HTTP path from @RequestMapping (common on controllers)
        String baseHttpPath = type.getAnnotationByName("RequestMapping")
                .map(this::extractAnnotationPath)
                .orElse(null);

        List<ParsedField> fields = type.getFields().stream()
                .flatMap(fd -> fd.getVariables().stream().map(v -> ParsedField.builder()
                        .name(v.getNameAsString())
                        .type(fd.getElementType().asString())
                        .annotations(fd.getAnnotations().stream().map(AnnotationExpr::getNameAsString).toList())
                        .build()))
                .toList();

        List<ParsedMethod> methods = type.getMethods().stream()
                .map(this::buildMethod)
                .toList();

        String layer = detectLayer(classAnnotations, name, superClass, interfaces);

        return ParsedClass.builder()
                .simpleName(name)
                .fullyQualifiedName(pkg.isEmpty() ? name : pkg + "." + name)
                .packageName(pkg)
                .filePath(filePath)
                .layer(layer)
                .classAnnotations(classAnnotations)
                .implementedInterfaces(interfaces)
                .superClass(superClass)
                .fields(fields)
                .methods(methods)
                .isInterface(isInterface)
                .isAbstract(isAbstract)
                .isEnum(isEnum)
                .baseHttpPath(baseHttpPath)
                .build();
    }

    private ParsedMethod buildMethod(MethodDeclaration md) {
        List<String> annotations = md.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString).toList();
        List<String> paramTypes = md.getParameters().stream()
                .map(p -> p.getType().asString()).toList();

        List<String> calledMethods = List.of();
        List<String> calledClasses = List.of();
        if (md.getBody().isPresent()) {
            BlockStmt body = md.getBody().get();
            calledMethods = body.findAll(MethodCallExpr.class).stream()
                    .map(MethodCallExpr::getNameAsString)
                    .distinct().toList();
            calledClasses = body.findAll(NameExpr.class).stream()
                    .map(NameExpr::getNameAsString)
                    .filter(n -> Character.isUpperCase(n.charAt(0)))
                    .distinct().toList();
        }

        // Extract HTTP mapping annotation (GetMapping, PostMapping, etc.)
        String httpMethod = null;
        String httpPath   = null;
        for (AnnotationExpr ann : md.getAnnotations()) {
            String annName = ann.getNameAsString();
            String verb = MAPPING_ANNOTATIONS.get(annName);
            if (verb == null && "RequestMapping".equals(annName)) {
                // @RequestMapping(method = RequestMethod.POST, value = "/path")
                verb = extractRequestMappingVerb(ann);
            }
            if (verb != null) {
                httpMethod = verb;
                httpPath   = extractAnnotationPath(ann);
                break;
            }
        }

        return ParsedMethod.builder()
                .name(md.getNameAsString())
                .returnType(md.getType().asString())
                .parameterTypes(paramTypes)
                .annotations(annotations)
                .calledMethods(calledMethods)
                .calledClasses(calledClasses)
                .isPublic(md.isPublic())
                .isStatic(md.isStatic())
                .httpMethod(httpMethod)
                .httpPath(httpPath)
                .build();
    }

    /**
     * Extracts the URL path value from a mapping annotation.
     * Handles: @GetMapping("/path"), @PostMapping(value="/path"),
     *          @RequestMapping(value={"/path"}, method=…)
     */
    private String extractAnnotationPath(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr smae) {
            return stripQuotesAndBraces(smae.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr nae) {
            return nae.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()) || "path".equals(p.getNameAsString()))
                    .findFirst()
                    .map(p -> stripQuotesAndBraces(p.getValue().toString()))
                    .orElse("/");
        }
        return "/";
    }

    /** Extracts RequestMethod.GET/POST/… from @RequestMapping(method = RequestMethod.POST). */
    private String extractRequestMappingVerb(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr nae) {
            return nae.getPairs().stream()
                    .filter(p -> "method".equals(p.getNameAsString()))
                    .findFirst()
                    .map(p -> {
                        String v = p.getValue().toString().toUpperCase();
                        if (v.contains("POST"))   return "POST";
                        if (v.contains("PUT"))    return "PUT";
                        if (v.contains("DELETE")) return "DELETE";
                        if (v.contains("PATCH"))  return "PATCH";
                        return "GET";
                    })
                    .orElse(null); // no method attribute → not a simple verb mapping
        }
        return null;
    }

    /** Strips surrounding quotes, array braces, and whitespace from annotation values. */
    private String stripQuotesAndBraces(String raw) {
        return raw.replaceAll("[{}\"]", "").trim();
    }

    private String detectLayer(List<String> annotations, String name, String superClass, List<String> interfaces) {
        if (annotations.contains("RestController") || annotations.contains("Controller") || name.endsWith("Controller") || name.endsWith("Resource"))
            return "CONTROLLER";
        if (annotations.contains("Service") || name.endsWith("Service") || name.endsWith("ServiceImpl") || name.endsWith("UseCase") || name.endsWith("Facade"))
            return "SERVICE";
        if (annotations.contains("Repository") || name.endsWith("Repository") || name.endsWith("Dao")
                || interfaces.stream().anyMatch(i -> i.contains("Repository") || i.contains("CrudRepository") || i.contains("JpaRepository")))
            return "REPOSITORY";
        if (annotations.contains("Component") || annotations.contains("EventListener") || name.endsWith("Handler") || name.endsWith("Consumer"))
            return "COMPONENT";
        if (superClass != null && superClass.contains("Exception"))
            return "EXCEPTION";
        if (annotations.contains("Configuration") || annotations.contains("ConfigurationProperties"))
            return "CONFIG";
        if (name.endsWith("Client") || name.endsWith("Gateway") || name.endsWith("Adapter"))
            return "CLIENT";
        if (annotations.contains("Entity") || name.endsWith("Entity"))
            return "ENTITY";
        // Likely DTO / value object
        if (isLikelyDto(name, annotations))
            return "DTO";
        return "OTHER";
    }

    private boolean isLikelyDto(String name, List<String> annotations) {
        return name.endsWith("Dto") || name.endsWith("DTO") || name.endsWith("Request")
                || name.endsWith("Response") || name.endsWith("Payload") || name.endsWith("Event")
                || annotations.contains("Value") || annotations.contains("Builder");
    }
}
