package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserIdTest {

    @Test
    void keepsTheSubjectAsIs() {
        assertThat(UserId.of("david").value()).isEqualTo("david");
        assertThat(UserId.of("David@Example.org").toString()).isEqualTo("David@Example.org");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> UserId.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
