package ai.mindconnect.agentrest.dto;

/**
 * One element of the session stream ({@code GET /api/sessions/{id}/stream}):
 * the wire event wrapped with its cursor and turn coordinates. {@code seq}
 * is what a client hands back as {@code afterSeq} on reconnect; {@code
 * turnId}/{@code run} let it filter one turn out of the shared stream.
 */
public record SessionStreamFrame(long seq, String turnId, int run, StreamEventFrame event) {
}
