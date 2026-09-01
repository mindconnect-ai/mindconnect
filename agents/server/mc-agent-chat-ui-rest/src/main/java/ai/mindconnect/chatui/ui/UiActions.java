package ai.mindconnect.chatui.ui;

import ai.mindconnect.ui.model.UiTrigger;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;

/**
 * Builds a {@link UiTrigger} from the controller method it should call,
 * instead of from a URL written out by hand.
 *
 * <pre>{@code
 * UiAction.danger("delete", "Delete")
 *         .onClick(trigger(on(AgentUiController.class).delete(agent.id())));
 * }</pre>
 *
 * <p>{@code on(...)} records the call on a proxy; both the path and the HTTP
 * verb are then read off the handler's own {@code @RequestMapping}. The point
 * is what a string cannot do: the compiler checks the arguments, renaming the
 * handler updates every caller, and "go to definition" lands on the code that
 * runs — no grep for a matching route.
 *
 * <p>Two constraints come from the recording proxy, neither of which any
 * action handler in this repo hits: the controller class and the called method
 * must not be {@code final}, and the return type must be proxyable — a
 * {@code String} handler (a view-name forward) cannot be referenced this way.
 * Those are navigation endpoints, which keep their literal URLs anyway.
 */
public final class UiActions {

    private UiActions() {
    }

    /**
     * Stands in for a value the CLIENT fills in, not the server: pass it where
     * a table row supplies its own id and the rendered URL keeps the literal
     * {@code {id}} placeholder that {@code UiTable.rowAction} expects.
     *
     * <pre>{@code
     * .rowAction(UiAction.secondary("view", "View")
     *         .onClick(trigger(on(AgentUiController.class).viewTool(agent.id(), ROW_ID))));
     * }</pre>
     *
     * <p>A sentinel rather than a string parameter because the handler's
     * signature is what gets checked — passing {@code ROW_ID} still proves the
     * argument is a tool id in the right position.
     */
    public static final java.util.UUID ROW_ID =
            java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** What {@link #ROW_ID} becomes in the rendered URL. */
    private static final String ROW_ID_PLACEHOLDER = "{id}";

    /** The trigger for a recorded call, e.g. {@code on(X.class).delete(id)}. */
    public static UiTrigger trigger(Object recordedCall) {
        return UiTrigger.api(verbOf(recordedCall), urlOf(recordedCall));
    }

    /**
     * Same, but the form named by {@code payloadNodeId} travels as the body.
     * Spell the form out rather than relying on the event bus inferring the
     * enclosing one — a control that sits outside its form would otherwise
     * submit whatever encloses it, or nothing.
     */
    public static UiTrigger trigger(Object recordedCall, String payloadNodeId) {
        return UiTrigger.api(verbOf(recordedCall), urlOf(recordedCall), payloadNodeId);
    }

    /**
     * The SSE variant: same derivation, but the trigger streams its response
     * instead of applying it in one go. For the handlers that return an
     * {@code SseEmitter} — sending a turn, regenerating one.
     */
    public static UiTrigger streaming(Object recordedCall, String payloadNodeId) {
        return UiTrigger.stream(verbOf(recordedCall), urlOf(recordedCall), payloadNodeId);
    }

    private static String urlOf(Object recordedCall) {
        // The baseUrl overload on purpose: the no-arg variant reads the
        // current request from RequestContextHolder and throws on any thread
        // without one — which is exactly where a streaming turn renders.
        // encode() matters: a path or query value carrying a space or a
        // reserved character would otherwise land raw in the URL. The strings
        // this replaces called URLEncoder by hand, and forgetting it here
        // would break exactly the ids that are hardest to notice — an
        // approval callId, a file name.
        String url = MvcUriComponentsBuilder
                .fromMethodCall(UriComponentsBuilder.newInstance(), recordedCall)
                .build().encode().toUriString();
        // Put the placeholder back AFTER building, so the braces are never
        // percent-encoded on their way through the URI builder.
        return url.replace(ROW_ID.toString(), ROW_ID_PLACEHOLDER);
    }

    /** The handler's own mapping decides the verb; an unannotated one is a GET. */
    private static String verbOf(Object recordedCall) {
        Method method = ((MvcUriComponentsBuilder.MethodInvocationInfo) recordedCall).getControllerMethod();
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        return mapping != null && mapping.method().length > 0 ? mapping.method()[0].name() : "GET";
    }
}
