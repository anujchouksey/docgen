package com.repoinsight.analyzer.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BusinessFlow {
    String name;
    String trigger;            // e.g. "POST /api/orders"
    String description;
    List<String> steps;
    List<String> invariants;   // business rules / preconditions
    List<String> sideEffects;  // events emitted, emails sent, etc.
}
