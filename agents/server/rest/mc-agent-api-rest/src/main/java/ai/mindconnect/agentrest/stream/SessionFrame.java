package ai.mindconnect.agentrest.stream;

/** A frame on a session's stream: which turn it belongs to, and the frame. */
public record SessionFrame(String turnId, TurnFrame frame) { }
