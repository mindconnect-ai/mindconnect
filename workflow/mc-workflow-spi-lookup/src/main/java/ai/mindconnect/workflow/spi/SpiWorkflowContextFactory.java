package ai.mindconnect.workflow.spi;

import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Creates a {@link WorkflowContextFactory} pre-configured by all
 * {@link WorkflowConfigurer} implementations found on the classpath via
 * {@link ServiceLoader}.
 *
 * <p>Each module that ships a
 * {@code META-INF/services/ai.mindconnect.workflow.api.execution.WorkflowConfigurer}
 * file is discovered and applied automatically.  No explicit wiring code is
 * needed — adding a module to the classpath is sufficient.
 *
 * <p>Usage:
 * <pre>{@code
 * // All step types and resolvers present on the classpath are registered:
 * WorkflowContextFactory factory = SpiWorkflowContextFactory.create();
 * WorkflowExecutorService service = new WorkflowExecutorService(factory);
 * }</pre>
 *
 * <p>To add custom configuration on top of SPI discovery, call
 * {@link #create()} and then modify the returned factory before building the
 * executor service:
 * <pre>{@code
 * WorkflowContextFactory factory = SpiWorkflowContextFactory.create();
 * factory.setBaseDir("/data/workflows");
 * WorkflowExecutorService service = new WorkflowExecutorService(factory);
 * }</pre>
 */
public final class SpiWorkflowContextFactory {

    private SpiWorkflowContextFactory() {}

    /**
     * Creates a {@link WorkflowContextFactory} and applies every
     * {@link WorkflowConfigurer} discoverable from the current thread's
     * context classloader.
     *
     * @return a fully configured factory ready for use
     */
    public static WorkflowContextFactory create() {
        return create(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Creates a {@link WorkflowContextFactory} and applies every
     * {@link WorkflowConfigurer} discoverable from {@code classLoader}.
     *
     * @param classLoader the classloader to use for service discovery
     * @return a fully configured factory ready for use
     */
    public static WorkflowContextFactory create(ClassLoader classLoader) {
        WorkflowContextFactory factory = new DefaultWorkflowContextFactory();

        ServiceLoader.load(WorkflowConfigurer.class, classLoader)
                .forEach(configurer -> configurer.configure(factory));

        return factory;
    }

    /**
     * Returns the list of all {@link WorkflowConfigurer} instances that would
     * be applied by {@link #create()}, without actually creating a factory.
     * Useful for diagnostics and testing.
     */
    public static List<WorkflowConfigurer> discoverConfigurers() {
        return discoverConfigurers(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Returns the list of all {@link WorkflowConfigurer} instances discoverable
     * from {@code classLoader}.
     */
    public static List<WorkflowConfigurer> discoverConfigurers(ClassLoader classLoader) {
        List<WorkflowConfigurer> found = new ArrayList<>();
        ServiceLoader.load(WorkflowConfigurer.class, classLoader).forEach(found::add);
        return found;
    }
}
