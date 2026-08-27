package ai.mindconnect.workflow.execution;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

/** Aggregates multiple exceptions thrown during parallel step execution. */
@RequiredArgsConstructor
public class MultipleExceptionsException extends Exception {

    @Getter
    private final List<Throwable> exceptions;

    @Override
    public String getMessage() {
        return "Multiple exceptions during parallel execution: "
                + exceptions.stream().map(Throwable::getMessage).collect(Collectors.joining(", "));
    }
}
