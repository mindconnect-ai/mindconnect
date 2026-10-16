- **agents:** **skills are a feature of their own.** The skill classes left
  `mc-agent-runtime-core` for `agents/features/skills/`: `mc-agent-skills-core`
  (package `ai.mindconnect.agent.skill`, formerly `ai.mindconnect.agent.runtime.skill`),
  `mc-agent-skills` (the file and in-memory stores, now in `…skill.adapter.file` and
  `…skill.adapter.memory`, and `SkillsFeature`, now `ai.mindconnect.agent.skill.feature`,
  replacing the module `mc-agent-runtime-feature-skills`) and `mc-agent-skills-pg`
  (`…skill.adapter.pg`). `SkillsFeature` picks the store by the persistence itself;
  `AgentRepositoryFactory` has no `skillRepository()` any more. The runtime core asks for
  skills through two hooks the `SkillCatalog` implements: it is a `PromptSection`, and a
  `SkillToolGrant` (`ai.mindconnect.agent.runtime.port.out`) that offers the `skill` tool.
  `SystemPromptRenderer`, `WorkingMemoryBuilder`, `LlmChatProvider`, `AgentTurnWorker` and
  `AgentChatService` no longer take a `SkillCatalog`; `DynamicToolActivations` takes a
  `SkillToolGrant`. The tool's name is `AgentDefinition.SKILL_TOOL`;
  `SkillCatalog.promptSection(…)` is `render(…)`.
