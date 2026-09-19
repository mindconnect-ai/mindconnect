package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.UserId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts a {@link ConnectedTool} on the accounts of whoever this call runs for.
 *
 * <p>Sits in the same chain as {@link AliasTool}, {@link DescribedTool} and
 * {@link PinnedParamsTool} and does the one thing none of them do: it turns
 * "this tool needs a mailbox" into "this tool runs on <em>your</em> mailbox,
 * and here are the ones you have".
 *
 * <h2>What the model is offered</h2>
 * A parameter per {@link ConnectionSpec#params()} — but only when there is
 * something to choose. With a single end and at most one connection there is
 * nothing to decide, so the parameter is left out entirely and the account is
 * implied: a user with one mailbox never sees the word "account". With two
 * connections it becomes an enum of exactly their keys, the default first, so
 * the model cannot name an account that does not exist.
 *
 * <h2>What happens before the tool runs</h2>
 * The connection parameters are taken back out of the arguments — the tool
 * never sees them — and resolved. A call that cannot be pointed at a usable
 * account is answered here, with the sentence that says what to do about it,
 * and the tool is never entered. That sentence is written once instead of in
 * every tool.
 */
public final class ConnectionBoundTool implements Tool {

    private final ConnectedTool delegate;
    private final ConnectionSpec spec;
    private final Connections connections;
    private final UserId userId;

    private ConnectionBoundTool(ConnectedTool delegate, ConnectionSpec spec,
                                Connections connections, UserId userId) {
        this.delegate = delegate;
        this.spec = spec;
        this.connections = connections;
        this.userId = userId;
    }

    /**
     * Wraps {@code delegate} when it is a {@link ConnectedTool} and its source
     * declared a {@link ConnectionSpec}; returns it unchanged otherwise, so a
     * tool that needs no account carries no wrapper at all.
     */
    public static Tool wrap(Tool delegate, ConnectionSpec spec, Connections connections, ToolCallScope scope) {
        if (!(delegate instanceof ConnectedTool connected) || spec == null) {
            return delegate;
        }
        return new ConnectionBoundTool(connected, spec,
                connections == null ? Connections.none() : connections,
                scope == null ? null : scope.userId());
    }

    @Override public String name() { return delegate.name(); }

    @Override public String description() { return delegate.description(); }

    @Override public boolean streamsResultToUser() { return delegate.streamsResultToUser(); }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = delegate.parametersSchema();
        Map<String, Map<String, Object>> offered = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ConnectionParam param : spec.params()) {
            List<ToolConnection> available = available(param);
            if (!offer(param, available)) {
                continue;                       // nothing to choose — the account is implied
            }
            offered.put(param.name(), property(param, available));
            if (param.required()) {
                required.add(param.name());
            }
        }
        return offered.isEmpty() ? schema : withProperties(schema, offered, required);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Map<String, Object> rest = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Map<String, ToolConnection> bound = new LinkedHashMap<>();
        for (ConnectionParam param : spec.params()) {
            Object named = rest.remove(param.name());
            String key = named == null ? null : String.valueOf(named).strip();
            Optional<ToolConnection> found = userId == null
                    ? Optional.empty()
                    : connections.resolve(userId, param.provider(), key);
            if (found.isEmpty()) {
                if (!param.required()) continue;
                return "Error: " + missing(param, key);
            }
            ToolConnection connection = found.get();
            if (!connection.usable()) {
                return "Error: your " + spec.title() + " \"" + connection.label() + "\" is not usable right now. "
                        + "Open it in your profile under Connections and connect it again.";
            }
            bound.put(param.name(), connection);
        }
        return delegate.execute(rest, new BoundConnections(bound));
    }

    // ── what to offer ───────────────────────────────────────────────────────

    private List<ToolConnection> available(ConnectionParam param) {
        return userId == null ? List.of() : connections.of(userId, param.provider());
    }

    /**
     * Whether the model should be asked at all. With two ends there is always
     * something to say — which is which — even when only one account exists.
     * With one end, one account is no choice, and offering an enum of one
     * value only spends context.
     */
    private boolean offer(ConnectionParam param, List<ToolConnection> available) {
        return spec.choosePerCall() ? !available.isEmpty() : available.size() > 1;
    }

    private Map<String, Object> property(ConnectionParam param, List<ToolConnection> available) {
        List<String> keys = available.stream().map(ToolConnection::key).toList();
        String choices = available.stream()
                .map(c -> c.key() + " (" + c.label() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("enum", keys);
        property.put("description", "Which " + spec.title() + " to use: " + choices
                + (param.required() && !spec.choosePerCall() ? ". Omitted means the default." : "."));
        return property;
    }

    /** The sentence for a call that has no account to run on. */
    private String missing(ConnectionParam param, String key) {
        List<ToolConnection> available = available(param);
        if (available.isEmpty()) {
            return "you have no " + spec.title() + " connected yet. Add one in your profile under "
                    + "Connections, then ask again.";
        }
        String yours = available.stream().map(ToolConnection::key)
                .reduce((a, b) -> a + ", " + b).orElse("");
        return "you have no " + spec.title() + " called \"" + key + "\". Yours are: " + yours + ".";
    }

    /** The delegate's schema with the connection properties added, leaving everything else as it was. */
    private static Map<String, Object> withProperties(Map<String, Object> schema,
                                                      Map<String, Map<String, Object>> offered,
                                                      List<String> required) {
        Map<String, Object> patched = schema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(schema);
        patched.putIfAbsent("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        if (patched.get("properties") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> properties.put(String.valueOf(key), value));
        }
        properties.putAll(offered);
        patched.put("properties", properties);

        if (!required.isEmpty()) {
            List<String> names = new ArrayList<>(required);
            Object was = patched.get("required");
            if (was instanceof Iterable<?> items) {
                items.forEach(item -> names.add(0, String.valueOf(item)));
            } else if (was instanceof Object[] items) {
                for (int i = items.length - 1; i >= 0; i--) names.add(0, String.valueOf(items[i]));
            }
            patched.put("required", names.stream().distinct().toList());
        }
        return patched;
    }
}
