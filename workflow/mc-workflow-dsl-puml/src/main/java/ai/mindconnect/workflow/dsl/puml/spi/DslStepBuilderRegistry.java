package ai.mindconnect.workflow.dsl.puml.spi;

import ai.mindconnect.workflow.domain.StepData;

import java.util.*;

/**
 * Registry of {@link DslStepBuilder} instances discovered via {@link ServiceLoader}.
 *
 * <p>Builders registered via
 * {@code META-INF/services/ai.mindconnect.workflow.dsl.puml.spi.DslStepBuilder}
 * are loaded at construction time.  Additional builders can be registered
 * programmatically via {@link #register(DslStepBuilder)}.
 *
 * <p>Type name matching is case-insensitive.
 *
 * <p>If no builder is found for a given type name the registry returns
 * {@code Optional.empty()}, leaving the caller to apply a fallback strategy
 * (e.g. reflection-based generic builder).
 */
public class DslStepBuilderRegistry {

    private final Map<String, DslStepBuilder> builders = new LinkedHashMap<>();

    /**
     * Creates a registry pre-populated with all builders discoverable via
     * {@link ServiceLoader} using the current thread's context class loader.
     */
    public DslStepBuilderRegistry() {
        // Built-in builders always present
        register(new AssignDslStepBuilder());
        loadOptionalBuilder("ai.mindconnect.workflow.dsl.puml.spi.CodeDslStepBuilder");

        // HttpDslStepBuilder.typeName() returns "http", but HttpCallData.getType()
        // returns "httpcall" (derived from class name). Register under both so that
        // PUML files written by the serializer (":httpcall ...") round-trip correctly,
        // while hand-written PUML using the shorter ":http ..." also works.
        loadOptionalBuilderUnderAlias("ai.mindconnect.workflow.dsl.puml.spi.HttpDslStepBuilder", "httpcall");
        loadOptionalBuilder("ai.mindconnect.workflow.dsl.puml.spi.HttpDslStepBuilder");

        // SPI-discovered builders
        ServiceLoader.load(DslStepBuilder.class).forEach(this::register);
    }

    /** Registers a builder, replacing any existing one with the same type name. */
    public void register(DslStepBuilder builder) {
        builders.put(builder.typeName().toLowerCase(Locale.ROOT), builder);
    }

    /**
     * Returns the builder for the given type name, or {@code Optional.empty()}
     * if none is registered.
     */
    public Optional<DslStepBuilder> find(String typeName) {
        return Optional.ofNullable(builders.get(typeName.toLowerCase(Locale.ROOT)));
    }

    /**
     * Builds a step using the registered builder, or returns {@code null} when
     * no builder is found.
     */
    public StepData buildOrNull(String typeName, String stepName, Map<String, String> props) {
        return find(typeName).map(b -> b.build(stepName, props)).orElse(null);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Tries to load a builder by class name; silently ignores if the class is
     * not on the classpath (optional module scenario).
     */
    private void loadOptionalBuilder(String className) {
        try {
            Class<?> cls = Class.forName(className);
            DslStepBuilder builder = (DslStepBuilder) cls.getDeclaredConstructor().newInstance();
            register(builder);
        } catch (ClassNotFoundException ignored) {
            // optional dependency not on classpath
        } catch (Exception e) {
            // log at warn level if a logger were available — silently skip for now
        }
    }

    /**
     * Like {@link #loadOptionalBuilder(String)} but registers the builder under
     * an explicit alias instead of (or in addition to) its own {@code typeName()}.
     */
    private void loadOptionalBuilderUnderAlias(String className, String alias) {
        try {
            Class<?> cls = Class.forName(className);
            DslStepBuilder builder = (DslStepBuilder) cls.getDeclaredConstructor().newInstance();
            builders.put(alias.toLowerCase(Locale.ROOT), builder);
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
        }
    }
}
