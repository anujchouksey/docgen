package com.repoinsight.analyzer.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class MethodCallNode {
    String className;
    String methodSignature;
    String layer;              // CONTROLLER | SERVICE | REPOSITORY | CLIENT | SCHEDULER
    IntegrationKind integrationKind;  // null if pure in-process
    String integrationTarget;          // e.g. "PostgreSQL", "Kafka:order.created", "Stripe"
    List<MethodCallNode> children;
    int depth;

    public enum IntegrationKind {
        DB, KAFKA_PRODUCER, KAFKA_CONSUMER, S3, CACHE, HTTP, THIRD_PARTY_SDK, NONE
    }
}
