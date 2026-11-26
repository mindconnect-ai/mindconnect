package ai.mindconnect.agent.runtime.capabilities.skill;

/** What the agent is told about a skill before loading it: enough to decide whether to. */
public record SkillSummary(String name, String description) { }
