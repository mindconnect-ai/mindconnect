package ai.mindconnect.agent.migration.earlier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the earlier runtime kept in the namespace the current call works in,
 * read as it lies, without the earlier runtime's classes. Empty where it never
 * ran.
 */
public interface EarlierRuntimeData {

    /** Each agent as the JSON the earlier runtime wrote. */
    List<String> agents();

    /** Each session as the earlier runtime's JSON object. */
    List<Map<String, Object>> sessions();

    /** Each message of a conversation as the message repository's JSON object, in their order. */
    List<Map<String, Object>> messages(String conversationId);

    /** The latest summary of a conversation, if it has one. */
    Optional<Summary> latestSummary(String conversationId);

    /**
     * @param text            what the summary says
     * @param throughSequence the place of the last message it covers
     */
    record Summary(String text, long throughSequence) {
    }

    /** Where the earlier runtime never kept anything. */
    static EarlierRuntimeData none() {
        return new EarlierRuntimeData() {
            @Override
            public List<String> agents() {
                return List.of();
            }

            @Override
            public List<Map<String, Object>> sessions() {
                return List.of();
            }

            @Override
            public List<Map<String, Object>> messages(String conversationId) {
                return List.of();
            }

            @Override
            public Optional<Summary> latestSummary(String conversationId) {
                return Optional.empty();
            }
        };
    }
}
