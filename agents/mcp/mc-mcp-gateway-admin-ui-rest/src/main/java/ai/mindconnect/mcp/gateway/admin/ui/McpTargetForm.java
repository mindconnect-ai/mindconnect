package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The target of a registration as JSON text, for editing in a form field.
 *
 * <p>A target is a small tree — image, mounts, env, flags — and its shape
 * depends on the type. A form of flat fields would either flatten that into
 * something lossy or need per-type field sets the UI toggles between. One
 * JSON field is honest about what is being edited, and it is the same text
 * that ends up in the registration file, so what an operator sees here is
 * what an operator finds on disk.
 *
 * <p>Editing structure as text is a first step, not the end state: the
 * per-type form with its own fields is what a UI should eventually offer.
 */
final class McpTargetForm {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpTargetForm() {
    }

    /** Pretty JSON for the editor, or a starter skeleton when there is none. */
    static String toJson(McpTarget target) {
        if (target == null) {
            return """
                    {
                      "type": "docker",
                      "image": "",
                      "mounts": [],
                      "env": {}
                    }""";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toNode(target));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * @throws IllegalArgumentException with a message meant for the operator
     */
    static McpTarget fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Target is required.");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Target is not valid JSON: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Target must be a JSON object.");
        }
        String type = node.path("type").asText("");
        return switch (type) {
            case "docker" -> new McpTarget.Docker(
                    required(node, "image"),
                    mounts(node.path("mounts")),
                    stringMap(node.path("env")),
                    stringList(node.path("runFlags")),
                    stringList(node.path("command")));
            case "process" -> {
                List<String> command = stringList(node.path("command"));
                if (command.isEmpty()) {
                    throw new IllegalArgumentException("A process target needs a non-empty \"command\" array.");
                }
                yield new McpTarget.Process(command, stringMap(node.path("env")));
            }
            case "http" -> {
                java.net.URI url;
                try {
                    url = new java.net.URI(required(node, "url"));
                } catch (java.net.URISyntaxException e) {
                    throw new IllegalArgumentException("Target \"url\" is not a valid URL: " + e.getMessage());
                }
                yield new McpTarget.Http(url, stringMap(node.path("headers")));
            }
            case "" -> throw new IllegalArgumentException(
                    "Target needs a \"type\": \"docker\", \"process\" or \"http\".");
            default -> throw new IllegalArgumentException(
                    "Unsupported target type \"" + type + "\" — supported: docker, process, http.");
        };
    }

    private static ObjectNode toNode(McpTarget target) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (target) {
            case McpTarget.Docker docker -> {
                node.put("type", "docker");
                node.put("image", docker.image());
                ArrayNode mounts = node.putArray("mounts");
                docker.mounts().forEach(mount -> {
                    ObjectNode m = mounts.addObject();
                    m.put("hostPath", mount.hostPath());
                    m.put("containerPath", mount.containerPath());
                });
                ObjectNode env = node.putObject("env");
                docker.env().forEach(env::put);
                if (!docker.runFlags().isEmpty()) {
                    ArrayNode flags = node.putArray("runFlags");
                    docker.runFlags().forEach(flags::add);
                }
                if (!docker.command().isEmpty()) {
                    ArrayNode command = node.putArray("command");
                    docker.command().forEach(command::add);
                }
            }
            case McpTarget.Process process -> {
                node.put("type", "process");
                ArrayNode command = node.putArray("command");
                process.command().forEach(command::add);
                ObjectNode env = node.putObject("env");
                process.env().forEach(env::put);
            }
            case McpTarget.Http http -> {
                node.put("type", "http");
                node.put("url", http.url().toString());
                ObjectNode headers = node.putObject("headers");
                http.headers().forEach(headers::put);
            }
        }
        return node;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Target is missing \"" + field + "\".");
        }
        return value;
    }

    private static List<McpTarget.Mount> mounts(JsonNode node) {
        List<McpTarget.Mount> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode mount : node) {
                out.add(new McpTarget.Mount(
                        required(mount, "hostPath"),
                        required(mount, "containerPath")));
            }
        }
        return out;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> out.add(item.asText()));
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
}
