package ai.mindconnect.workflow.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The suspension is gone — already resumed, or discarded. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NoSuchInstanceException extends RuntimeException {
    public NoSuchInstanceException(String instanceId) {
        super("No suspended run '" + instanceId + "' — it may already have been resumed.");
    }
}
