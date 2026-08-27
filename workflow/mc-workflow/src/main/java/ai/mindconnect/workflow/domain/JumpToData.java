package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Unconditionally jumps to the named step within the same container. */
@Data
@EqualsAndHashCode(callSuper = true)
public class JumpToData extends BaseStepData {

    /** Name (or expression resolving to a name) of the target step. */
    private String jumpTo;
}
