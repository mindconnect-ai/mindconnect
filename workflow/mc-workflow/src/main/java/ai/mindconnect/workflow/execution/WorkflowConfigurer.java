package ai.mindconnect.workflow.execution;

/**
 * SPI for contributing configuration to a {@link WorkflowContextFactory}.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} when
 * {@code mc-workflow-spi-lookup} is on the classpath.  Each module that wants
 * to auto-register its step types or expression resolvers ships a file:
 * <pre>
 *   META-INF/services/ai.mindconnect.workflow.api.execution.WorkflowConfigurer
 * </pre>
 * containing the fully-qualified class name of its configurer.
 *
 * <p>Step modules implement {@link #configure} by registering against
 * {@code factory.getStepInstanceFactory()}:
 * <pre>
 *   factory.getStepInstanceFactory().register(MyData.class, MyStep::new);
 * </pre>
 *
 * <p>Expression-resolver modules install their resolver via
 * {@link WorkflowContextFactory#setExpressionResolver} or, when multiple
 * resolvers may be present, by appending to a
 * {@link CompositeExpressionResolver}:
 * <pre>
 *   ExpressionResolver existing = factory.getExpressionResolver();
 *   if (existing instanceof CompositeExpressionResolver c) {
 *       c.add(new MyResolver());
 *   } else {
 *       CompositeExpressionResolver composite = new CompositeExpressionResolver();
 *       if (existing != null) composite.add(existing);
 *       composite.add(new MyResolver());
 *       factory.setExpressionResolver(composite);
 *   }
 * </pre>
 *
 * <p>Configurers must have a public no-arg constructor — {@link java.util.ServiceLoader}
 * instantiates them reflectively.
 */
public interface WorkflowConfigurer {

    /**
     * Applies this module's contributions to {@code factory}.
     * Called once per {@link WorkflowContextFactory} during SPI-based setup.
     */
    void configure(WorkflowContextFactory factory);
}
