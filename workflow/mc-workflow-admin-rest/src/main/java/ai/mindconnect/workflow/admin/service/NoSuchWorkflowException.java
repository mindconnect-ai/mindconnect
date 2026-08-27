package ai.mindconnect.workflow.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class NoSuchWorkflowException extends RuntimeException {
    public NoSuchWorkflowException(String workflowId) {
        super("No such workflow: " + workflowId);
    }
}
