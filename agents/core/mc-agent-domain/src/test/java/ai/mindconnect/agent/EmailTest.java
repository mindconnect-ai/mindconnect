package ai.mindconnect.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void anAddressIsKeptTrimmedAndLowerCase_soOnePersonIsOneEntry() {
        assertThat(Email.of("  David@Example.COM ")).isEqualTo(Email.of("david@example.com"));
        assertThat(Email.of("david@example.com").value()).isEqualTo("david@example.com");
        assertThat(Email.of("david@example.com")).hasToString("david@example.com");
    }

    @Test
    void onlyBlankIsRefused_theShapeIsCheckedAtTheBoundaries() {
        assertThatThrownBy(() -> Email.of(" ")).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> Email.of(null)).hasMessageContaining("must not be blank");
        // The type takes it; isAddress is what a form asks. See the TODO in Email.
        assertThat(Email.of("david").value()).isEqualTo("david");
        assertThat(Email.isAddress("david")).isFalse();
        assertThat(Email.isAddress("david@example.com")).isTrue();
        assertThat(Email.isAddress("da vid@example.com")).isFalse();
        assertThat(Email.isAddress("a@b@c")).isFalse();
        assertThat(Email.isAddress("@example.com")).isFalse();
        assertThat(Email.isAddress("david@")).isFalse();
    }

    @Test
    void parseAnswersEmptyForNothingToRead() {
        assertThat(Email.parse("david@example.com")).contains(Email.of("david@example.com"));
        assertThat(Email.parse(null)).isEmpty();
        assertThat(Email.parse("  ")).isEmpty();
    }

    @Test
    void aBareNameGetsTheInstallationsDomain_andAnAddressIsLeftAlone() {
        assertThat(Email.qualified("David", "erni.mindconnect.ai"))
                .isEqualTo(Email.of("david@erni.mindconnect.ai"));
        assertThat(Email.qualified("david", "@erni.mindconnect.ai"))
                .as("a domain written with the @ means the same").isEqualTo(Email.of("david@erni.mindconnect.ai"));
        assertThat(Email.qualified("guest@example.com", "erni.mindconnect.ai"))
                .as("a guest keeps their own address").isEqualTo(Email.of("guest@example.com"));
        assertThatThrownBy(() -> Email.qualified("david", null))
                .hasMessageContaining("no default domain");
        assertThatThrownBy(() -> Email.qualified(" ", "example.com"))
                .hasMessageContaining("must not be blank");
    }

    @Test
    void inJsonItIsTheStringItself() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Email> addresses = List.of(Email.of("david@example.com"), Email.of("alice@example.com"));

        String json = mapper.writeValueAsString(addresses);

        assertThat(json).isEqualTo("[\"david@example.com\",\"alice@example.com\"]");
        assertThat(mapper.readValue("\"David@Example.com\"", Email.class))
                .isEqualTo(Email.of("david@example.com"));
    }
}
