package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.tool.ToolSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saving the settings form is a replacement: the controller reads the
 * {@code param.*} fields the form carried and stores exactly those. So the
 * form has to render a field for every parameter that has something stored —
 * whatever it leaves out is thrown away on the next save.
 */
class ToolSettingsComponentTest {

    private static String renderedJson(ToolSettings settings, Map<String, String> sourceParameters)
            throws Exception {
        return new ObjectMapper().writeValueAsString(
                new ToolSettingsComponent("gmail_search_emails", settings,
                        "Searches mail.", sourceParameters, null).render());
    }

    private static Map<String, String> source() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("query", "the search query");
        return parameters;
    }

    @Test
    void a_stored_override_survives_a_source_that_cannot_be_resolved() throws Exception {
        // The point of this test: it did not. With no resolvable source the
        // form rendered no parameter fields, and the next save — switching
        // the tool off, say — silently dropped every parameter override.
        ToolSettings stored = new ToolSettings(null, null, Map.of("query", "Gmail search syntax"));

        String json = renderedJson(stored, Map.of());

        assertThat(json).contains("param.query");
        assertThat(json).contains("Gmail search syntax");
    }

    @Test
    void an_override_for_a_parameter_the_tool_lost_stays_visible_and_says_so() throws Exception {
        ToolSettings stored = new ToolSettings(null, null, Map.of("maxResults", "at most 20"));

        String json = renderedJson(stored, source());

        assertThat(json).contains("param.query");        // the source's own
        assertThat(json).contains("param.maxResults");   // and the orphan
        assertThat(json).contains("no such parameter");
    }

    @Test
    void a_parameter_the_source_offers_is_rendered_with_its_text_as_the_hint() throws Exception {
        String json = renderedJson(ToolSettings.none(), source());

        assertThat(json).contains("param.query");
        assertThat(json).contains("the search query");
    }
}
