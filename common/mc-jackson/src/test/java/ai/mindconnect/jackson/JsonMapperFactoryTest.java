package ai.mindconnect.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

class JsonMapperFactoryTest {

    private static final ContributedModule.Word HELLO = new ContributedModule.Word("hello");

    private final JsonMapperFactory factory = new JsonMapperFactory(List.of(new ContributedModule()));

    @Test
    void every_mapper_has_the_modules_it_was_given() throws Exception {
        assertThat(factory.create().writeValueAsString(HELLO)).isEqualTo("\"hello\"");
        assertThat(factory.builder().build().writeValueAsString(HELLO)).isEqualTo("\"hello\"");
        assertThat(factory.create(new JsonFactory()).writeValueAsString(HELLO)).isEqualTo("\"hello\"");
    }

    @Test
    void nothing_but_the_modules_is_set() {
        ObjectMapper plain = new ObjectMapper();
        ObjectMapper made = factory.create();

        assertThat(made.getSerializationConfig().getSerializationFeatures())
                .isEqualTo(plain.getSerializationConfig().getSerializationFeatures());
        assertThat(made.getDeserializationConfig().getDeserializationFeatures())
                .isEqualTo(plain.getDeserializationConfig().getDeserializationFeatures());
    }

    @Test
    void what_a_caller_sets_on_the_builder_is_kept() throws Exception {
        ObjectMapper indenting = factory.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

        assertThat(indenting.writeValueAsString(Map.of("a", HELLO))).isEqualTo("{\n  \"a\" : \"hello\"\n}");
    }
}
