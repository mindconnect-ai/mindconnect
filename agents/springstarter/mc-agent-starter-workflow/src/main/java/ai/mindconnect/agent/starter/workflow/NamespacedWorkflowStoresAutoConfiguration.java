package ai.mindconnect.agent.starter.workflow;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.file.FileWorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Workflows live in a namespace like everything else of the agents area. The
 * workflow area itself is namespace-free — its adapters ask a neutral
 * partition per call, its auto-configurations fix one partition per process —
 * so this configuration, on the agents side, hands out stores whose partition
 * is the namespace the host's {@link ScopeSupplier} names per call. It runs
 * ahead of the workflow area's own configurations, whose beans then back off.
 *
 * <p>The same seam carries the request into a workflow run, on the request
 * thread or the one the run is streamed from: the admin UI asks for a
 * {@code RunThreadContext} and this configuration answers with the scope's
 * namespace and the request's user as the run's {@code ToolCallScope}.
 */
@AutoConfiguration(
        afterName = {
                "ai.mindconnect.agent.starter.namespace.NamespaceAutoConfiguration",
                "ai.mindconnect.agent.starter.file.FilePersistenceAutoConfiguration",
                "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig"},
        beforeName = {
                "ai.mindconnect.workflow.admin.WorkflowAdminAutoConfiguration",
                "ai.mindconnect.workflow.persistence.pg.WorkflowPostgresAutoConfiguration"})
@ConditionalOnBean(ScopeSupplier.class)
public class NamespacedWorkflowStoresAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowDataRepository.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    WorkflowDataRepository namespacedWorkflowDataRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                                  ScopeSupplier scope) {
        return new FileWorkflowDataRepository(Path.of(baseDir), partition(scope));
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    WorkflowInstanceRepository namespacedWorkflowInstanceRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                                          ScopeSupplier scope) {
        return new FileWorkflowInstanceRepository(Path.of(baseDir), partition(scope));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.workflow.persistence.pg.PgWorkflowDataRepository")
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    static class Postgres {

        /** What the file store kept under {@code mindconnect.data.base-dir} is imported once for every namespace found there. */
        @Bean
        @ConditionalOnMissingBean(WorkflowDataRepository.class)
        WorkflowDataRepository namespacedWorkflowDataRepository(DataSource dataSource, ScopeSupplier scope,
                                                                @Value("${mindconnect.data.base-dir:data}") String baseDir) {
            return new ai.mindconnect.workflow.persistence.pg.PgWorkflowRepositoryFactory(
                    ai.mindconnect.jdbc.Sql.of(dataSource), partition(scope), Path.of(baseDir)).workflowDataRepository();
        }

        @Bean
        @ConditionalOnMissingBean(WorkflowInstanceRepository.class)
        WorkflowInstanceRepository namespacedWorkflowInstanceRepository(DataSource dataSource, ScopeSupplier scope,
                                                                        @Value("${mindconnect.data.base-dir:data}") String baseDir) {
            return new ai.mindconnect.workflow.persistence.pg.PgWorkflowRepositoryFactory(
                    ai.mindconnect.jdbc.Sql.of(dataSource), partition(scope), Path.of(baseDir)).workflowInstanceRepository();
        }
    }

    /** The namespace of the current call, as the workflow area's neutral partition. */
    private static Supplier<String> partition(ScopeSupplier scope) {
        return () -> scope.namespace().value();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.workflow.admin.run.RunThreadContext")
    static class AdminRuns {

        /**
         * The admin's runs resolve {@code ${env.X}} through the host's {@link EnvVarResolver} — on a
         * server the user's, the namespace's and the process's variables — like the LLM gateways do.
         */
        @Bean
        @ConditionalOnMissingBean
        ai.mindconnect.workflow.admin.service.WorkflowAdminService workflowAdminService(
                WorkflowDataRepository store, WorkflowInstanceRepository instances,
                ObjectProvider<EnvVarResolver> environment) {
            EnvVarResolver vars = environment.getIfAvailable(EnvVarResolver::system);
            return new ai.mindconnect.workflow.admin.service.WorkflowAdminService(store, instances, vars.shared()::asMap);
        }

        /**
         * A run started from the admin screens or the REST API works in the namespace of the
         * request that started it, and its tool and agent steps act for the request's user —
         * their mail, their connections — not for the workflow user, who has none. A
         * {@link ToolCallScope} already bound to the thread wins; a request without a user
         * starts a run on nobody's behalf, as before.
         */
        @Bean
        @ConditionalOnMissingBean
        ai.mindconnect.workflow.admin.run.RunThreadContext workflowRunThreadContext(ScopeSupplier scope) {
            return body -> {
                boolean bound = scope instanceof ThreadBoundScopeSupplier threadBound && threadBound.isBound();
                Runnable inNamespace = bound ? ((ThreadBoundScopeSupplier) scope).wrap(body) : body;
                Optional<ToolCallScope> caller = ToolCallScope.current()
                        .or(() -> userOf(scope, bound).map(ToolCallScope::detached));
                if (caller.isEmpty()) return inNamespace;
                return () -> caller.get().runWith(() -> {
                    inNamespace.run();
                    return null;
                });
            };
        }

        /** The request's user; an unbound thread has none, whatever a fallback scope says. */
        private static Optional<UserId> userOf(ScopeSupplier scope, boolean bound) {
            if (scope instanceof ThreadBoundScopeSupplier && !bound) return Optional.empty();
            return scope.get().userIfAny();
        }
    }
}
