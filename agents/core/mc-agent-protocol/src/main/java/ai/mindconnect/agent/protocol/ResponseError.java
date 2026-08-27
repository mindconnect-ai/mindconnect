package ai.mindconnect.agent.protocol;

/**
 * Terminal failure of a response. {@code code} is a stable machine-readable
 * discriminator (e.g. "llm_unavailable", "depth_limit", "cancelled_parent");
 * {@code message} is human-readable.
 */
public record ResponseError(String code, String message) {}
