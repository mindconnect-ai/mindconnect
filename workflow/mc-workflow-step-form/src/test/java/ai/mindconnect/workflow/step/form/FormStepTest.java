package ai.mindconnect.workflow.step.form;

import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class FormStepTest {

    /** A workflow whose only step is a form step suspends, exactly like a halt. */
    @Test
    void suspendsLikeAHalt() {
        WorkflowData wf = new WorkflowData();
        wf.setName("form-wf");
        wf.setSteps(new ArrayList<>(List.of(formStep())));

        WorkflowContextFactory factory = new DefaultWorkflowContextFactory();
        // The engine has no built-in mapping for this module's step; the
        // configurer is what teaches it. This mirrors what the SPI does at runtime.
        new FormStepConfigurer().configure(factory);

        WorkflowResult result = new WorkflowExecutorService(factory).executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isHalted()).isTrue();
    }

    /**
     * The load-bearing question: a UiNode form survives the workflow serializer.
     * If this holds, a workflow using the step stores and reloads with no extra
     * wiring — UiNode brings its own Jackson type info.
     */
    @Test
    void theFormRoundTripsThroughTheWorkflowMapper() throws Exception {
        WorkflowData wf = new WorkflowData();
        wf.setName("form-wf");
        wf.setSteps(new ArrayList<>(List.of(formStep())));

        ObjectMapper mapper = WorkflowObjectMapperFactory.create();
        String json = mapper.writeValueAsString(wf);
        WorkflowData restored = mapper.readValue(json, WorkflowData.class);

        Assertions.assertThat(restored.getSteps().get(0)).isInstanceOf(FormStepData.class);
        FormStepData step = (FormStepData) restored.getSteps().get(0);
        Assertions.assertThat(step.getForm()).isInstanceOf(UiForm.class);

        UiForm form = (UiForm) step.getForm();
        Assertions.assertThat(form.getId()).isEqualTo("approval-form");
        Assertions.assertThat(json).contains("\"@class\"")   // the step type
                .contains("approval-form");                  // the form survived
    }

    private static FormStepData formStep() {
        FormStepData step = new FormStepData();
        step.setName("collect-approval");
        UiNode form = UiForm.of("approval-form", "Approve?")
                .field(UiField.text("approver", "Approver", null).asEditable().asRequired())
                .field(UiField.select("verdict", "Verdict", "approve",
                        List.of(UiField.Option.of("approve", "approve"),
                                UiField.Option.of("reject", "reject"))).asEditable());
        step.setForm(form);
        return step;
    }
}
