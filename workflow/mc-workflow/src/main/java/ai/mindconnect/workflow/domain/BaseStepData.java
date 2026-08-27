package ai.mindconnect.workflow.domain;

import lombok.Data;

/**
 * Convenient base implementation of {@link StepData}.
 * Derives the type string from the concrete class name by convention
 * ("FooData" → "foo"), which keeps JSON compact without requiring explicit
 * type fields in every subclass.
 */
@Data
public abstract class BaseStepData implements StepData {

    private String name;
    private String assignResultToVar;

    @Override
    public String getType() {
        return this.getClass().getSimpleName().replace("Data", "").toLowerCase();
    }

    /** No-op setter so serialization frameworks can round-trip the type field. */
    public void setType(String type) {
    }
}
