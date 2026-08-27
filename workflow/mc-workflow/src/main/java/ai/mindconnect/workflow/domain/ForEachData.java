package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Iterates over a list variable and executes the child steps for each element.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ForEachData extends BaseStepContainerData {

    /** Name of the variable (or expression) that holds the iterable. */
    private String loopOver;

    /** Variable name bound to the current iteration element. */
    private String runVar;

    /** Variable name bound to the current iteration index (0-based). */
    private String indexVar;

    /** When true, iterations run concurrently via a thread pool. */
    private boolean parallel;

    /** When true, iteration results are joined into a single String. */
    private boolean joinResults;

    /** Delimiter used when {@code joinResults} is true. Defaults to empty string. */
    private String joinDelimiter;
}
