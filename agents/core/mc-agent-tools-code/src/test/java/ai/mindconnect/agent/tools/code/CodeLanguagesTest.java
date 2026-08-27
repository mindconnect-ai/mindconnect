package ai.mindconnect.agent.tools.code;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeLanguagesTest {

    @Test
    void defaultsOfferPythonAndNode() {
        Map<String, CodeLanguages.CodeLanguage> languages = CodeLanguages.defaults();

        assertThat(languages.get("python").image()).isEqualTo("python:3.12-slim");
        assertThat(languages.get("python").command()).containsExactly("python3", "-");
        assertThat(languages.get("node").command()).containsExactly("node", "-");
    }

    @Test
    void parseOverridesImageAndKeepsKnownCommand() {
        var languages = CodeLanguages.parse("python=python:3.13-slim");

        assertThat(languages.get("python").image()).isEqualTo("python:3.13-slim");
        assertThat(languages.get("python").command()).containsExactly("python3", "-");
        assertThat(languages.get("node").image()).isEqualTo("node:22-slim");
    }

    @Test
    void parseAddsNewLanguageWithExplicitCommand() {
        var languages = CodeLanguages.parse("ruby=ruby:3.3-slim|ruby -");

        assertThat(languages.get("ruby").image()).isEqualTo("ruby:3.3-slim");
        assertThat(languages.get("ruby").command()).isEqualTo(List.of("ruby", "-"));
    }

    @Test
    void parseUnknownLanguageWithoutCommandFallsBackToStdinConvention() {
        var languages = CodeLanguages.parse("lua=lua:5.4");

        assertThat(languages.get("lua").command()).isEqualTo(List.of("lua", "-"));
    }

    @Test
    void parseRejectsEntriesWithoutImage() {
        assertThatThrownBy(() -> CodeLanguages.parse("python"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
