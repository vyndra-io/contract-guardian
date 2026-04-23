package io.contractguardian.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FindingTest {

    @Test
    void breakingFactory_setsSeverity() {
        Finding f = Finding.breaking(ContractType.KAFKA_AVRO, "test.avsc", "field-removed", "Field removed");
        assertThat(f.severity()).isEqualTo(Severity.BREAKING);
        assertThat(f.line()).isEqualTo(-1);
        assertThat(f.fix()).isNull();
        assertThat(f.detail()).isNull();
    }

    @Test
    void breakingFactoryWithDetails_setsAllFields() {
        Finding f = Finding.breaking(ContractType.KAFKA_AVRO, "test.avsc", "field-removed",
                "Field removed", "detail", "Add default value");
        assertThat(f.severity()).isEqualTo(Severity.BREAKING);
        assertThat(f.fix()).isEqualTo("Add default value");
        assertThat(f.detail()).isEqualTo("detail");
    }

    @Test
    void warningFactory_setsSeverity() {
        Finding f = Finding.warning(ContractType.REST_OPENAPI, "api.yaml", "deprecated", "Field deprecated");
        assertThat(f.severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void infoFactory_setsSeverity() {
        Finding f = Finding.info(ContractType.KAFKA_AVRO, "test.avsc", "new-schema", "New schema");
        assertThat(f.severity()).isEqualTo(Severity.INFO);
    }
}
