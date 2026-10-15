package ai.mindconnect.agentrest.dto;

public record TokenFrame(String token, boolean finished) {
    public static TokenFrame of(String token) { return new TokenFrame(token, false); }
    public static TokenFrame done() { return new TokenFrame(null, true); }
}
