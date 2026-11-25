package ai.mindconnect.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

class JsonMapperFactoryTest {

    private static final AnnouncedModule.Word HELLO = new AnnouncedModule.Word("hello");

    @Test
    void a_mapper_has_every_module_that_announces_itself() throws Exception {
        assertThat(JsonMapperFactory.create().writeValueAsString(HELLO)).isEqualTo("\"hello\"");
        assertThat(JsonMapperFactory.create().writeValueAsString(Instant.parse("2026-01-01T00:00:00Z")))
                .as("the Java time types, announced by their own module").isEqualTo("1767225600.000000000");
    }

    @Test
    void a_built_mapper_and_one_over_another_format_have_them_too() throws Exception {
        assertThat(JsonMapperFactory.builder().build().writeValueAsString(HELLO)).isEqualTo("\"hello\"");
        assertThat(JsonMapperFactory.create(new JsonFactory()).writeValueAsString(HELLO)).isEqualTo("\"hello\"");
    }

    @Test
    void nothing_but_the_modules_is_set() {
        ObjectMapper plain = new ObjectMapper();
        ObjectMapper made = JsonMapperFactory.create();

        assertThat(made.getSerializationConfig().getSerializationFeatures())
                .isEqualTo(plain.getSerializationConfig().getSerializationFeatures());
        assertThat(made.getDeserializationConfig().getDeserializationFeatures())
                .isEqualTo(plain.getDeserializationConfig().getDeserializationFeatures());
        assertThat(made.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        assertThat(made.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    @Test
    void what_a_caller_sets_on_the_builder_is_kept() throws Exception {
        ObjectMapper indenting = JsonMapperFactory.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

        assertThat(indenting.writeValueAsString(Map.of("a", HELLO))).isEqualTo("{\n  \"a\" : \"hello\"\n}");
    }
}
