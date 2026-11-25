package ai.mindconnect.agentrest.stream;

import ai.mindconnect.agent.runtime.api.port.in.Subscription;

/**
 * What attaching to a session's stream hands back.
 *
 * @param liveTurnId       the turn underway in the session; {@code null}
 *                         when it is idle
 * @param firstBufferedSeq the oldest frame still buffered; one beyond what
 *                         the client asked from means frames were lost and
 *                         the history is to be read anew
 * @param latestSeq        the newest frame there was when attaching
 */
public record StreamAttachment(Subscription subscription, String liveTurnId, long firstBufferedSeq,
                               long latestSeq) { }
