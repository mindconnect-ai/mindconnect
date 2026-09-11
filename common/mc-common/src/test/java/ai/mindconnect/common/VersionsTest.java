package ai.mindconnect.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionsTest {

    @Test
    void aSaveWithoutVersionIsNotChecked() {
        assertThat(Versions.next(null, null, "Agent", "a")).isEqualTo(1);
        assertThat(Versions.next(7L, null, "Agent", "a")).isEqualTo(8);
    }

    @Test
    void theEditedVersionMustBeTheStoredOne() {
        assertThat(Versions.next(3L, 3L, "Agent", "a")).isEqualTo(4);
        assertThat(Versions.next(null, 0L, "Agent", "a")).as("nothing stored counts as 0").isEqualTo(1);

        assertThatThrownBy(() -> Versions.next(4L, 3L, "Agent", "a"))
                .isInstanceOf(StaleVersionException.class)
                .hasMessageContaining("Agent a")
                .satisfies(e -> {
                    StaleVersionException stale = (StaleVersionException) e;
                    assertThat(stale.code()).isEqualTo(StaleVersionException.CODE);
                    assertThat(stale.expectedVersion()).isEqualTo(3);
                    assertThat(stale.storedVersion()).isEqualTo(4);
                });
    }
}
