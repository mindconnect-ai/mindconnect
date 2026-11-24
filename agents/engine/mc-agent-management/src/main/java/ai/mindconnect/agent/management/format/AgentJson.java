package ai.mindconnect.agent.management.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.management.api.ManagedAgent;

/**
 * An agent as a file: what a seed, an export and the REST API carry. The
 * format is the managed agent as it is stored. A file written before that —
 * the agent as the earlier runtime knew it — is read too, so an installation
 * keeps its exports and its seeds; nothing is written that way any more.
 */
public final class AgentJson {

    private final ObjectMapper json;

    /** @param json reads and writes time stamps as ISO-8601 text, as the stores do */
    public AgentJson(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @throws IllegalArgumentException for text that is no agent in either form
     */
    public ManagedAgent read(String content) {
        try {
            JsonNode agent = json.readTree(content);
            if (agent == null || !agent.isObject()) {
                throw new IllegalArgumentException("An agent file holds one JSON object");
            }
            // The definition is what only the stored form has; the earlier one kept its fields flat.
            return agent.has("definition") ? json.treeToValue(agent, ManagedAgent.class)
                    : EarlierAgentJson.read(agent, json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not an agent: " + e.getOriginalMessage(), e);
        }
    }

    public String write(ManagedAgent agent) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(agent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent " + agent.definition().name() + " cannot be written", e);
        }
    }
}
