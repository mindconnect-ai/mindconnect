package ai.mindconnect.agentrest.dto;

/**
 * The first frame of every session stream: where the buffer starts and ends,
 * and whether a turn is running right now. {@code firstBufferedSeq} greater
 * than the requested {@code afterSeq + 1} means the replay has a gap — the
 * client should refresh from the persisted history instead of trusting the
 * tail. {@code liveTurnId} is null when the session is idle.
 */
public record AttachedFrame(String type, long firstBufferedSeq, long latestSeq,
                            String liveTurnId, Integer liveRun) {

    public static AttachedFrame of(long firstBufferedSeq, long latestSeq,
                                   java.util.UUID liveTurnId, Integer liveRun) {
        return new AttachedFrame("attached", firstBufferedSeq, latestSeq,
                liveTurnId == null ? null : liveTurnId.toString(), liveRun);
    }
}
