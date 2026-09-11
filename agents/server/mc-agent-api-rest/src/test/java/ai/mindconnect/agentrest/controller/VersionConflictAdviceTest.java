package ai.mindconnect.agentrest.controller;

import ai.mindconnect.common.StaleVersionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionConflictAdviceTest {

    @Test
    void aStaleVersionIsAConflictNamingTheStoredVersion() {
        var response = new VersionConflictAdvice().conflict(
                new StaleVersionException("AgentDefinition", "a1", 3, 5));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
                .containsEntry("code", "CONFLICT")
                .containsEntry("expectedVersion", 3L)
                .containsEntry("storedVersion", 5L);
    }
}
