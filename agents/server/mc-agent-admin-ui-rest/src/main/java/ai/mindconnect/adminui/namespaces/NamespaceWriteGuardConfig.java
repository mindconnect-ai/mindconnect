package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the stores a namespace's admins own in {@link NamespaceWriteGuard}, so
 * that the role is asked at the write itself and not only at the request that
 * led to it.
 *
 * <p>It is a {@link BeanPostProcessor} because these stores are built
 * elsewhere — one auto-configuration per persistence backend, each already
 * routed per namespace — and the question is the installation's, not theirs.
 * Wrapping them here keeps that knowledge in one file instead of in five
 * modules that would all have to learn what a role is.
 *
 * <p>Only where there are namespaces to have roles in: a host that embeds the
 * Admin UI without the namespace starter keeps what it had. That is decided at
 * the write, by a {@link NamespaceWriteGuard#deferred deferred} guard, and not
 * as a condition on this class: it is found by component scanning, before the
 * auto-configurations that define {@link NamespaceService} and
 * {@link ScopeSupplier}, so a {@code @ConditionalOnBean} here never matched and
 * no store was guarded.
 */
@Configuration(proxyBeanMethods = false)
public class NamespaceWriteGuardConfig {

    /**
     * The guard is built lazily from providers: this post-processor runs while
     * the context is still coming up, and asking for beans too early would drag
     * half the application into existence in the wrong order.
     */
    @Bean
    static BeanPostProcessor namespaceWriteGuards(ObjectProvider<NamespaceService> namespaces,
                                                  ObjectProvider<ScopeSupplier> scope) {
        return new BeanPostProcessor() {
            private final NamespaceWriteGuard guard =
                    NamespaceWriteGuard.deferred(namespaces::getIfAvailable, scope::getIfAvailable);

            private NamespaceWriteGuard guard() {
                return guard;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                if (bean instanceof GuardedRepositories.Agents
                        || bean instanceof GuardedRepositories.LlmConfigs
                        || bean instanceof GuardedRepositories.Skills
                        || bean instanceof GuardedRepositories.Workflows
                        || bean instanceof GuardedRepositories.McpServers) {
                    return bean;
                }
                if (bean instanceof AgentDefinitionRepository agents) {
                    return new GuardedRepositories.Agents(agents, guard());
                }
                if (bean instanceof LlmConfigRepository configs) {
                    return new GuardedRepositories.LlmConfigs(configs, guard());
                }
                if (bean instanceof SkillRepository skills) {
                    return new GuardedRepositories.Skills(skills, guard());
                }
                if (bean instanceof WorkflowDataRepository workflows) {
                    return new GuardedRepositories.Workflows(workflows, guard());
                }
                if (bean instanceof McpRegistryAdmin mcp) {
                    return new GuardedRepositories.McpServers(mcp, guard());
                }
                return bean;
            }
        };
    }
}
