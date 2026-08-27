package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.domain.StepData;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Produces a Jackson {@link ObjectMapper} pre-configured for workflow
 * serialization/deserialization.
 *
 * <h3>Design</h3>
 * All Jackson annotations ({@code @JsonTypeInfo}, {@code @JsonIgnore}, etc.)
 * are applied via <em>mixins</em> rather than on the domain classes themselves.
 * This keeps {@code mc-workflow-api} completely free of Jackson.
 *
 * <h3>Polymorphism strategy</h3>
 * {@code StepData} uses {@code @JsonTypeInfo(use = Id.CLASS)} so that the full
 * class name is stored as {@code "@class"} in JSON.  This requires no central
 * registry of subtypes and supports custom steps automatically — any class on
 * the classpath can be deserialized as long as it extends {@link StepData}.
 *
 * <p>Usage:
 * <pre>{@code
 * ObjectMapper mapper = WorkflowObjectMapperFactory.create();
 * }</pre>
 */
public class WorkflowObjectMapperFactory {

    private WorkflowObjectMapperFactory() {}

    /**
     * Creates a new {@link ObjectMapper} with all workflow mixins registered
     * and pretty-printing enabled.
     */
    public static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        registerMixins(mapper);
        return mapper;
    }

    /**
     * Registers workflow mixins on an existing mapper.
     * Use this when you want to combine the workflow configuration with your
     * own mapper settings (e.g. date formats, modules, naming strategies).
     */
    public static void registerMixins(ObjectMapper mapper) {
        // Polymorphic type info for the StepData hierarchy
        mapper.addMixIn(StepData.class, StepDataMixin.class);

        // Read/write Schema as canonical JSON Schema (and old bare-name lists).
        mapper.registerModule(SchemaJackson.module());
    }

    // -----------------------------------------------------------------------
    // Mixins — Jackson annotations live here, not on domain classes
    // -----------------------------------------------------------------------

    /**
     * Adds {@code @JsonTypeInfo} to the {@link StepData} interface so Jackson
     * can round-trip the full step hierarchy without any annotation on the
     * domain class itself.
     *
     * The {@code "@class"} property stores the fully-qualified class name,
     * which means custom steps are supported out of the box without any
     * registration step.
     */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.CLASS,
            property = "@class")
    abstract static class StepDataMixin {
        // marker — no members needed
    }
}
