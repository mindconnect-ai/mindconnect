package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class BaseStepContainerData extends BaseStepData implements StepContainerData {

    private String resultFrom;
    private List<StepData> steps = new ArrayList<>();

    @Override
    public void addSteps(StepData... pSteps) {
        this.steps.addAll(Arrays.asList(pSteps));
    }

    @Override
    public void setSteps(List<StepData> steps) {
        this.steps = steps;
    }
}
