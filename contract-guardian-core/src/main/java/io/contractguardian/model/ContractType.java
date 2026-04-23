package io.contractguardian.model;

/**
 * Types of contracts that can be scanned for breaking changes.
 */
public enum ContractType {

    /** Apache Avro schema for Kafka topics. */
    KAFKA_AVRO,

    /** JSON Schema for Kafka topics. */
    KAFKA_JSON_SCHEMA,

    /** Protocol Buffers schema for Kafka topics. */
    KAFKA_PROTOBUF,

    /** OpenAPI specification for REST endpoints. */
    REST_OPENAPI,

    /** gRPC service definition. */
    REST_GRPC,

    /** SQL migration file (Flyway, Liquibase). */
    DB_MIGRATION,

    /** JPA entity annotation changes. */
    DB_JPA_ENTITY,

    /** JSONB column shape evolution. */
    DB_JSONB
}
