package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.common.env.EnvVarResolver;
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

/**
 * Workflows live in a namespace like everything else of the agents area. The
 * workflow area itself is namespace-free — its adapters take a neutral
 * partition, its auto-configurations bind one partition per process — so
 * this configuration, on the agents side, hands out the routed stores
 * instead: one adapter per namespace behind each port, chosen per call by
 * the host's {@link ScopeSupplier}. It runs ahead of the workflow area's
 * own configurations, whose beans then back off.
 *
 * <p>The same seam carries the request's scope onto the thread a workflow
 * run is streamed from: the admin UI asks for a {@code RunThreadContext}
 * and this configuration answers with the scope's, when the host has one.
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
        return NamespaceRouted.route(WorkflowDataRepository.class, scope,
                ns -> new FileWorkflowDataRepository(Path.of(baseDir), ns.value()));
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    WorkflowInstanceRepository namespacedWorkflowInstanceRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                                          ScopeSupplier scope) {
        return NamespaceRouted.route(WorkflowInstanceRepository.class, scope,
                ns -> new FileWorkflowInstanceRepository(Path.of(baseDir), ns.value()));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.workflow.persistence.pg.PgWorkflowDataRepository")
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    static class Postgres {

        @Bean
        @ConditionalOnMissingBean(WorkflowDataRepository.class)
        WorkflowDataRepository namespacedWorkflowDataRepository(DataSource dataSource, ScopeSupplier scope) {
            ai.mindconnect.jdbc.Sql sql = ai.mindconnect.jdbc.Sql.of(dataSource);
            // The schema is shared (the namespace is a column): create it once here, not on each namespace's first call.
            new ai.mindconnect.workflow.persistence.pg.PgWorkflowDataRepository(sql, Namespace.DEFAULT.value()).initSchema();
            return NamespaceRouted.route(WorkflowDataRepository.class, scope,
                    ns -> new ai.mindconnect.workflow.persistence.pg.PgWorkflowDataRepository(sql, ns.value()));
        }

        @Bean
        @ConditionalOnMissingBean(WorkflowInstanceRepository.class)
        WorkflowInstanceRepository namespacedWorkflowInstanceRepository(DataSource dataSource, ScopeSupplier scope) {
            ai.mindconnect.jdbc.Sql sql = ai.mindconnect.jdbc.Sql.of(dataSource);
            new ai.mindconnect.workflow.persistence.pg.PgWorkflowInstanceRepository(sql, Namespace.DEFAULT.value()).initSchema();
            return NamespaceRouted.route(WorkflowInstanceRepository.class, scope,
                    ns -> new ai.mindconnect.workflow.persistence.pg.PgWorkflowInstanceRepository(sql, ns.value()));
        }
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

        /** A streamed run works in the namespace of the request that started it. */
        @Bean
        @ConditionalOnMissingBean
        ai.mindconnect.workflow.admin.run.RunThreadContext workflowRunThreadContext(ScopeSupplier scope) {
            if (scope instanceof ThreadBoundScope bound) {
                return body -> bound.isBound() ? bound.wrap(body) : body;
            }
            return ai.mindconnect.workflow.admin.run.RunThreadContext.NONE;
        }
    }
}
