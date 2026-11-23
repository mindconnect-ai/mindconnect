package ai.mindconnect.agent.runtime.capabilities.permission;

/**
 * One rule about one tool.
 *
 * @param toolName the tool's name, or {@link #ANY} for every tool
 */
public record PermissionRule(String toolName, PermissionEffect effect) {

    public static final String ANY = "*";

    public boolean covers(String name) {
        return ANY.equals(toolName) || toolName.equals(name);
    }
}
