package com.repoinsight.static_analysis.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ParsedField {
    String name;
    String type;                 // simple or generic, e.g. "KafkaTemplate<String, Object>"
    List<String> annotations;   // @Autowired, @Value, @Qualifier …
}
