package ai.mindconnect.agent.service.approval;

/** How far a human's "allow" reaches. */
public enum ApprovalScope {

    /** Exactly this one call — the next call of the same tool asks again. */
    ONCE,

    /**
     * A standing rule ({@code approvedTools}) on the ROOT session, inherited
     * by every sub-agent of the conversation — and every already-parked call
     * of the same tool passes its gate re-check on wake.
     */
    SESSION;

    /** Lenient parse for URL/form params ({@code once}/{@code session}); unknown → {@link #ONCE}. */
    public static ApprovalScope fromParam(String value) {
        return "session".equalsIgnoreCase(value) ? SESSION : ONCE;
    }
}
