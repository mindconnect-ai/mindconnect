package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a registration from its JSON form.
 *
 * <p>Hand-written rather than annotation-driven so that
 * {@code mc-mcp-gateway-core} stays free of Jackson: the port's types are
 * shared with a future HTTP client that may serialize differently, and a
 * sealed hierarchy needs a discriminator either way. Doing it here also
 * buys error messages that name the file and the field.
 *
 * <p>Shape:
 * <pre>{@code
 * {
 *   "id": "gmail",
 *   "displayName": "Gmail",
 *   "description": "...",
 *   "enabled": true,
 *   "toolNamePrefix": "gmail",
 *   "target": {
 *     "type": "docker",
 *     "image": "mcp/gmail:latest",
 *     "mounts": [ { "hostPath": "~/.gmail-mcp", "containerPath": "/root/.gmail-mcp" } ],
 *     "env": { "KEY": "value" },
 *     "runFlags": [ "--memory=512m" ],
 *     "command": [ ]
 *   }
 * }
 * }</pre>
 */
final class McpRegistrationJson {

    private McpRegistrationJson() {
    }

    /** The JSON form of a registration, in the shape {@link #read} expects. */
    static ObjectNode write(McpServerRegistration registration, JsonNodeFactory nodes) {
        // Kein namespace im Dokument: er steckt im Verzeichnis. Zwei Quellen
        // für dieselbe Wahrheit würden irgendwann auseinanderlaufen.
        ObjectNode root = nodes.objectNode();
        root.put("id", registration.id().value());
        root.put("displayName", registration.displayName());
        if (registration.description() != null) {
            root.put("description", registration.description());
        }
        root.put("enabled", registration.enabled());
        root.put("toolNamePrefix", registration.toolNamePrefix());
        root.set("target", writeTarget(registration.target(), nodes));
        if (registration.updatedAt() != null) {
            root.put("updatedAt", registration.updatedAt().toString());
        }
        return root;
    }

    private static ObjectNode writeTarget(McpTarget target, JsonNodeFactory nodes) {
        ObjectNode node = nodes.objectNode();
        switch (target) {
            case McpTarget.Docker docker -> {
                node.put("type", "docker");
                node.put("image", docker.image());
                ArrayNode mounts = node.putArray("mounts");
                for (McpTarget.Mount mount : docker.mounts()) {
                    ObjectNode m = mounts.addObject();
                    m.put("hostPath", mount.hostPath());
                    m.put("containerPath", mount.containerPath());
                }
                putMap(node.putObject("env"), docker.env());
                putList(node.putArray("runFlags"), docker.runFlags());
                putList(node.putArray("command"), docker.command());
            }
            case McpTarget.Process process -> {
                node.put("type", "process");
                putList(node.putArray("command"), process.command());
                putMap(node.putObject("env"), process.env());
            }
            case McpTarget.Http http -> {
                node.put("type", "http");
                node.put("url", http.url().toString());
                putMap(node.putObject("headers"), http.headers());
            }
        }
        return node;
    }

    private static void putMap(ObjectNode target, Map<String, String> values) {
        values.forEach(target::put);
    }

    private static void putList(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }

    static McpServerRegistration read(JsonNode node, String source) {
        String id = text(node, "id", source);
        JsonNode targetNode = node.get("target");
        if (targetNode == null || !targetNode.isObject()) {
            throw new IllegalArgumentException("missing 'target' object in " + source);
        }
        return new McpServerRegistration(
                McpServerId.of(id),
                node.path("displayName").asText(null),
                node.path("description").asText(null),
                node.path("enabled").asBoolean(true),
                node.path("toolNamePrefix").asText(id),
                target(targetNode, source),
                node.hasNonNull("updatedAt") ? Instant.parse(node.get("updatedAt").asText()) : null);
    }

    private static McpTarget target(JsonNode node, String source) {
        String type = text(node, "type", source);
        return switch (type) {
            case "docker" -> new McpTarget.Docker(
                    text(node, "image", source),
                    mounts(node.path("mounts"), source),
                    stringMap(node.path("env")),
                    stringList(node.path("runFlags")),
                    stringList(node.path("command")));
            case "process" -> new McpTarget.Process(
                    stringList(node.path("command")),
                    stringMap(node.path("env")));
            case "http" -> new McpTarget.Http(
                    URI.create(text(node, "url", source)),
                    stringMap(node.path("headers")));
            default -> throw new IllegalArgumentException(
                    "unsupported target type '" + type + "' in " + source
                            + " (supported: docker, process, http)");
        };
    }

    private static List<McpTarget.Mount> mounts(JsonNode node, String source) {
        List<McpTarget.Mount> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode m : node) {
                out.add(new McpTarget.Mount(
                        text(m, "hostPath", source),
                        text(m, "containerPath", source)));
            }
        }
        return out;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) out.add(n.asText());
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private static String text(JsonNode node, String field, String source) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing '" + field + "' in " + source);
        }
        return value.asText();
    }
}
