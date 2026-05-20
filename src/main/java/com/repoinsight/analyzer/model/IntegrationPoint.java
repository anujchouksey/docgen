package com.repoinsight.analyzer.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class IntegrationPoint {
    String category;      // DB | KAFKA | S3 | CACHE | HTTP | THIRD_PARTY
    String name;          // e.g. "PostgreSQL via JPA", "Kafka topic: order.created"
    String classRef;      // source class where detected
    String methodRef;
    String detectionPattern; // what triggered detection
    String direction;     // READ | WRITE | BOTH | PRODUCE | CONSUME
}
