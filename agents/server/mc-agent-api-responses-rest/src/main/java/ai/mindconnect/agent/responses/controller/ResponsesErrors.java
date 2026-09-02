package ai.mindconnect.agent.responses.controller;

import ai.mindconnect.agent.responses.ModelResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Failures in the shape an OpenAI client can read.
 *
 * <p>Without this the container's own error page answers, and an SDK reports
 * a mistyped model as {@code InternalServerError} with a stack-trace-shaped
 * body — the caller learns that something broke, but not that the name was
 * wrong or what names exist. The client library parses
 * {@code {"error": {"type", "message"}}}, so that is what is sent.
 *
 * <p>Scoped to this package: the rest of the application keeps its own error
 * handling, which speaks to its own clients.
 */
@RestControllerAdvice(basePackageClasses = ResponsesController.class)
public class ResponsesErrors {

    private static final Logger log = LoggerFactory.getLogger(ResponsesErrors.class);

    /** A model name that is neither an agent nor an llm-config. */
    @ExceptionHandler(ModelResolver.UnknownModelException.class)
    public ResponseEntity<Map<String, Object>> unknownModel(ModelResolver.UnknownModelException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error("invalid_request_error", e.getMessage(), "model"));
    }

    /** A request this server understands but cannot accept as sent. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error("invalid_request_error", e.getMessage(), null));
    }

    /**
     * Anything else. The message is passed on rather than hidden: this API
     * is reached by developers pointing a client at their own server, and a
     * generic "internal error" would cost them the one clue they have.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Responses API failed", e);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("server_error", message, null));
    }

    private static Map<String, Object> error(String type, String message, String param) {
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("type", type);
        error.put("message", message);
        if (param != null) {
            error.put("param", param);
        }
        return Map.of("error", error);
    }
}
