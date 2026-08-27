package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** A named group of steps — useful for scoping variables and readability. */
@Data
@EqualsAndHashCode(callSuper = true)
public class BlockData extends BaseStepContainerData {
}
