package ai.mindconnect.agent.tools.web;

import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

/**
 * Lazy holder for the {@link OkHttpClient} the web tools share.
 *
 * <p>The client is built inside this module rather than pulled from the
 * host application's Spring context: the LLM-gateway's primary
 * {@code OkHttpClient} bean has {@code readTimeout=0} (so SSE streams don't
 * abort mid-token), which is the wrong default for a one-shot
 * page fetch — a flaky server would hang the agent indefinitely. Keeping
 * the client local makes the tool's timeout policy self-contained and
 * decoupled from how the host wires its other HTTP needs.
 *
 * <p>Timeouts:
 * <ul>
 *   <li>connect: 15s — DNS + TLS handshake should never take longer.</li>
 *   <li>read: 30s — the server has 30s to send the next byte; perfect for
 *       slow CDNs without rewarding hung connections.</li>
 *   <li>write: 15s.</li>
 *   <li>call: 60s — hard ceiling on the whole request, no matter what.</li>
 * </ul>
 *
 * <p>Both {@link WebReadToolFactory} and {@link WebSearchToolFactory} reuse
 * the same instance — OkHttp's internal connection pool makes that
 * cheaper than a fresh client per tool.
 */
final class WebToolsHttpClient {

    private WebToolsHttpClient() {}

    private static final OkHttpClient INSTANCE = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();

    static OkHttpClient get() { return INSTANCE; }
}
