package ai.mindconnect.llm.adapter;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code ai.mindconnect.llm.wire} logger: at debug level it shows what
 * went over the wire, the request as JSON with media payloads cut out and
 * the answer as the accumulated text. Off by default, because a request
 * carries the whole conversation.
 */
public final class LlmWireLog {

    private static final Logger wire = LoggerFactory.getLogger("ai.mindconnect.llm.wire");

    private LlmWireLog() {
    }

    /** Whether the wire log is on; a gateway accumulates the answer for it only then. */
    public static boolean enabled() {
        return wire.isDebugEnabled();
    }

    public static void request(ObjectWriter prettyWriter, ObjectNode requestNode) {
        if (!wire.isDebugEnabled()) return;
        try {
            wire.debug("→ stream request:\n{}", prettyWriter.writeValueAsString(TraceRedaction.redactMedia(requestNode)));
        } catch (Exception e) {
            wire.debug("→ stream request: <serialise failed: {}>", e.getMessage());
        }
    }

    public static void response(int textTokenCount, CharSequence text) {
        wire.debug("← stream response (accumulated, {} text tokens):\n{}", textTokenCount, text);
    }
}
