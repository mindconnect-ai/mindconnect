package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.service.ConnectionService;
import ai.mindconnect.schema.Schema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the installed tools want a user to connect, and what that user has
 * connected — the one place the tool registry and the connection store meet.
 *
 * <p>Both sides are optional: a host that assembled no tools declares nothing,
 * and one that keeps no connections cannot store anything. Either way this
 * answers empty rather than failing, so the profile page renders a sentence
 * instead of an error.
 */
@Service
public class ToolConnections {

    private final ObjectProvider<ToolRegistry> tools;
    private final ObjectProvider<ConnectionService> connections;

    public ToolConnections(ObjectProvider<ToolRegistry> tools, ObjectProvider<ConnectionService> connections) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** What the classpath asks to be connected, one entry per provider. */
    public List<ConnectionSpec> specs() {
        ToolRegistry registry = tools.getIfAvailable();
        return registry == null ? List.of() : registry.connectionSpecs();
    }

    public Optional<ConnectionSpec> spec(String provider) {
        return specs().stream().filter(s -> s.provider().equals(provider)).findFirst();
    }

    /** True when this host can store a connection at all. */
    public boolean available() {
        return connections.getIfAvailable() != null;
    }

    /** What {@code user} has attached for {@code provider}, their default first. */
    public List<Connection> of(UserId user, String provider) {
        ConnectionService service = connections.getIfAvailable();
        return service == null ? List.of() : service.of(user, provider);
    }

    /** The service, for the controller that changes something; empty on a host without a store. */
    public Optional<ConnectionService> service() {
        return Optional.ofNullable(connections.getIfAvailable());
    }

    /**
     * Which fields of a form are secrets, as the schema says — everything a
     * {@code Format.PASSWORD} marks. The split between the readable half and
     * the encrypted half is made here and nowhere else, so a tool cannot get
     * it wrong and a form cannot be tricked into it.
     */
    public static Set<String> secretFields(Schema schema) {
        if (schema == null || schema.getProperties() == null) {
            return Set.of();
        }
        Set<String> secrets = new LinkedHashSet<>();
        schema.getProperties().forEach((name, property) -> {
            if (property.getFormat() == Schema.Format.PASSWORD) secrets.add(name);
        });
        return secrets;
    }

    /** Whether any installed tool asks for a connection this host could store. */
    public boolean anythingToConnect() {
        return available() && !specs().isEmpty();
    }
}
