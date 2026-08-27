package ai.mindconnect.agent.memory.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Polymorphic memory configuration for an AgentDefinition.
 * The concrete subtype determines which MemoryStrategy implementation is used;
 * each subtype carries the parameters relevant to that strategy.
 * <p>
 * A null value on AgentDefinition means "use the system default"
 * — currently {@link SummarizingWindowConfig#DEFAULT}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NoMemoryConfig.class,           name = "none"),
        @JsonSubTypes.Type(value = WindowedMemoryConfig.class,     name = "windowed"),
        @JsonSubTypes.Type(value = SummarizingWindowConfig.class,  name = "summarizing_window"),
        @JsonSubTypes.Type(value = AutoCompactConfig.class,        name = "auto_compact"),
        @JsonSubTypes.Type(value = FullHistoryMemoryConfig.class,  name = "full")
})
public sealed interface MemoryConfig
        permits NoMemoryConfig, WindowedMemoryConfig, SummarizingWindowConfig,
                AutoCompactConfig, FullHistoryMemoryConfig {

    /** Stable name used for logging and /memory display. Matches the JSON {@code kind} tag. */
    String kind();
}
