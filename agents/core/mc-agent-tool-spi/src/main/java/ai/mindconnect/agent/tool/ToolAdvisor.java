package ai.mindconnect.agent.tool;

import ai.mindconnect.common.Namespace;

import java.util.Map;
import java.util.UUID;

/**
 * Filter chain around every tool invocation.
 *
 * <p>The runtime walks all {@link ToolAdvisor} beans (in {@link #order()}
 * order) wrapped around a single tool call: each advisor sees the
 * {@link Invocation} on the way in and the {@link Result} on the way out,
 * and may mutate either. Calling
 * {@link Chain#proceed(Invocation) chain.proceed(inv)} runs the next
 * advisor down — or, at the tail, the actual {@link Tool#execute(Map)}.
 *
 * <p>Typical uses:
 * <ul>
 *   <li><b>Append progress hints</b> (e.g. open-todo counter after every
 *       call) by intercepting the result and {@link Result#append}-ing.</li>
 *   <li><b>Sanitize arguments</b> before they reach the tool (block
 *       dangerous bash patterns, normalise paths).</li>
 *   <li><b>Redact PII</b> from the result text (email addresses in Gmail
 *       responses, secrets in shell output).</li>
 *   <li><b>Short-circuit</b> with a synthetic result without calling
 *       {@link Chain#proceed} at all (rate limiting, permission denied).</li>
 *   <li><b>Audit / wire-trace</b> by reading the {@link Invocation} +
 *       {@link Result} and proceeding unchanged.</li>
 * </ul>
 *
 * <p>An advisor that {@link #applies(Invocation) doesn't apply} to the
 * current call is skipped entirely. Throwing from {@link #around} is
 * propagated to the runtime's standard tool-failure path (no extra
 * try/catch in advisor code).
 */
public interface ToolAdvisor {

    /**
     * Wraps one tool invocation. Must call {@code chain.proceed(inv)} unless
     * the advisor is intentionally short-circuiting.
     */
    Result around(Invocation inv, Chain chain) throws Exception;

    /**
     * Lower runs further out — sees raw input and final output. Negative
     * values are allowed. Default 0 = middle of the chain.
     */
    default int order() { return 0; }

    /**
     * Per-call opt-out. The runtime skips this advisor when it returns
     * {@code false}, jumping straight to the next one. Lets advisors keep
     * scope-checks (specific tool name, specific agent) outside of
     * {@link #around} for cheaper short-circuiting.
     */
    default boolean applies(Invocation inv) { return true; }

    // -----------------------------------------------------------------------
    // Records
    // -----------------------------------------------------------------------

    /**
     * Everything the runtime knows about one tool call. Immutable —
     * advisors create modified copies via the {@code with*} helpers.
     *
     * @param toolName  the tool's registered name (e.g. {@code "bash"})
     * @param arguments JSON-shaped argument map the LLM produced
     * @param namespace tenant scope
     * @param userId    end-user identifier (may be {@code "anonymous"})
     * @param sessionId the chat session this call belongs to
     * @param agentDefinitionId which agent issued the call
     * @param toolCallId LLM-supplied id for round-tripping tool results
     */
    record Invocation(
            String toolName,
            Map<String, Object> arguments,
            Namespace namespace,
            String userId,
            UUID sessionId,
            UUID agentDefinitionId,
            String toolCallId
    ) {
        /** Returns a copy with the supplied argument map. */
        public Invocation withArguments(Map<String, Object> next) {
            return new Invocation(toolName, next, namespace, userId,
                    sessionId, agentDefinitionId, toolCallId);
        }
    }

    /**
     * What the LLM ends up seeing. {@link #text} is the tool result string
     * (or an error description on failure). {@link #failed} drives the
     * runtime's failure-event vs success-event branch — advisors usually
     * don't flip this, but a sanitizer may downgrade a successful but
     * blocked call to {@code failed=true} with an explanatory text.
     *
     * @param text   text the LLM consumes as the tool result
     * @param failed {@code true} when the tool was unknown, threw, or was
     *               short-circuited as a failure
     */
    record Result(String text, boolean failed) {

        public static Result ok(String text) { return new Result(text, false); }
        public static Result error(String text) { return new Result(text, true); }

        /** Returns a copy with the result text replaced. */
        public Result withText(String next) { return new Result(next, failed); }

        /**
         * Returns a copy with {@code extra} appended after a blank line.
         * Convenient for advisors that add a progress footer / status hint
         * without disturbing the original output.
         */
        public Result append(String extra) {
            if (extra == null || extra.isEmpty()) return this;
            return new Result(text + "\n\n" + extra, failed);
        }
    }

    /**
     * Continuation handle. {@code proceed(inv)} runs the next advisor — or
     * the actual tool at the chain's tail — and returns its {@link Result}.
     * Advisors that fan out or retry may call it more than once; advisors
     * that short-circuit don't call it at all.
     */
    interface Chain {
        Result proceed(Invocation inv) throws Exception;
    }
}
