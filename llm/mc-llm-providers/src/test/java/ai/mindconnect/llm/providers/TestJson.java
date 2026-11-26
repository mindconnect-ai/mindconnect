package ai.mindconnect.llm.providers;

import ai.mindconnect.llm.jackson.LlmJsonModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one mapper the tests of this module read and write JSON with, configured like a host's:
 * with the time module, so the stores' dates round-trip the way they do in the apps.
 */
public final class TestJson {

    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new LlmJsonModule());

    private TestJson() {
    }
}
