package ai.mindconnect.agent.runtime.capabilities.permission;

/** What a rule says about a tool. */
public enum PermissionEffect {

    /** Offered and run without asking. */
    ALLOW,

    /** Offered; every call waits for the user's approval unless the session remembers one. */
    ASK,

    /** Not offered at all: the model does not learn the tool exists. */
    DENY
}
