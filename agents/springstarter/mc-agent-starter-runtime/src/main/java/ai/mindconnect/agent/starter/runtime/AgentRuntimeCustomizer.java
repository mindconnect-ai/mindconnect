package ai.mindconnect.agent.starter.runtime;

import ai.mindconnect.agent.builder.AgentRuntimeBuilder;

/**
 * A hook into the builder before the runtime is built — for an application
 * that wants to install a feature of its own, replace a default, or set a
 * property the starter does not know. Every bean of this type is applied,
 * in order, after the starter configured the shipped features.
 */
@FunctionalInterface
public interface AgentRuntimeCustomizer {

    void customize(AgentRuntimeBuilder builder);
}
