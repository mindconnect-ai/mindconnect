package ai.mindconnect.workflow.admin.run;

import ai.mindconnect.schema.Schema;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowInputsTest {

    static WorkflowData reply() {
        WorkflowData wf = new WorkflowData();
        wf.setName("Polite reply");
        wf.setParams(Schema.object()
                .prop("text", Schema.string())
                .prop("tone", Schema.string().defaultValue("friendly"))
                .prop("words", Schema.integer())
                .require("text"));
        return wf;
    }

    @Test
    void values_are_typed_and_defaults_fill_what_was_left_out() {
        Map<String, Object> in = WorkflowInputs.prepare(reply(), Map.of("text", "Hi", "words", "80"), false);
        assertThat(in).containsEntry("text", "Hi").containsEntry("words", 80L).containsEntry("tone", "friendly");
    }

    @Test
    void a_fixed_set_of_values_is_cut_down_to_what_the_workflow_declares() {
        Map<String, Object> in = WorkflowInputs.prepare(reply(),
                Map.of("text", "Hi", "subject", "Re: offer", "bcc", "boss@example.com"), true);
        assertThat(in).containsOnlyKeys("text", "tone");
        assertThat(WorkflowInputs.prepare(reply(), Map.of("text", "Hi", "extra", 1), false)).containsKey("extra");
    }

    @Test
    void a_missing_required_input_is_named_before_the_run() {
        assertThatThrownBy(() -> WorkflowInputs.prepare(reply(), Map.of("text", " "), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Polite reply needs the input text.");
    }
}
