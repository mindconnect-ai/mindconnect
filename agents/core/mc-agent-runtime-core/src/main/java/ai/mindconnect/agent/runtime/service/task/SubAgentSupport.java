package ai.mindconnect.agent.runtime.service.task;

/**
 * Whether this runtime lets an agent delegate to others — the inline
 * {@code run_agent} / {@code run_agents} tools, sub-agent sessions, the
 * recursion — and how deep. The mechanics stay in the turn loop; this is
 * the switch the sub-agents feature turns on. Without it an agent's roster
 * is ignored and a delegation call answers with an error.
 */
public interface SubAgentSupport {

    boolean enabled();

    /** The deepest chain of sub-agents allowed; a turn beyond it fails. */
    int maxDepth();

    static SubAgentSupport disabled() {
        return new SubAgentSupport() {
            @Override public boolean enabled() { return false; }
            @Override public int maxDepth() { return 0; }
            @Override public String toString() { return "SubAgentSupport.disabled"; }
        };
    }

    static SubAgentSupport enabled(int maxDepth) {
        return new SubAgentSupport() {
            @Override public boolean enabled() { return true; }
            @Override public int maxDepth() { return maxDepth; }
            @Override public String toString() { return "SubAgentSupport.enabled(maxDepth=" + maxDepth + ")"; }
        };
    }
}
