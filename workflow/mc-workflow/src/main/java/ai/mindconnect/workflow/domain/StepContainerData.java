package ai.mindconnect.workflow.domain;

import java.util.List;

/**
 * Marks a {@link StepData} as a container that owns a sequential list of child steps.
 */
public interface StepContainerData extends StepData {

    List<StepData> getSteps();

    void setSteps(List<StepData> steps);

    void addSteps(StepData... steps);

    /**
     * Optional: name of a variable whose value becomes the result of this container.
     * If null the container produces no result of its own.
     */
    String getResultFrom();

    void setResultFrom(String resultFrom);
}
