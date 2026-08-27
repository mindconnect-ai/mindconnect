# mc-agent-tool-spi

What a tool IS and how it gets bound — extracted from `mc-agent-runtime` so
the tool modules stop depending on an entire agent runtime to implement one
interface. Concept: [15-module-split](../../doc/architecture/concepts/15-module-split.md).

```
ai.mindconnect.agent.port.in
  Tool                 name · description · parameter schema · execute(args)
  ToolFactory          binds an AgentTool config into a Tool for one call scope
  ToolRegistry         what exists, per namespace
  ToolAdvisor          the filter chain around every invocation
  ToolEnvironment      configuration strings and services a factory may ask for
  ToolCallScope        namespace · user · session · agent of one invocation
  MultiToolProvider    one factory, several tools

ai.mindconnect.agent.domain
  AgentTool            how an agent configures a tool (enabled, overrides)

ai.mindconnect.agent.service
  SpiToolRegistry      ServiceLoader-based registry over the factories
  ToolRegistryRef · AliasTool · PinnedParamsTool · MapToolEnvironment
```

Dependencies: `mc-common` and `slf4j-api`. Package names are unchanged, so
nothing had to adjust an import — only the POMs changed.

## What is deliberately NOT here

Anything that needs the agent domain rather than the tool contract:
`ToolSearchTool` and `DynamicToolActivations` write activations onto the
SESSION, `TodoListService` owns its own domain, and the workflow tools invoke
agents. Those modules keep their runtime dependency until the domain module
exists (concept 15, step 2).

## Effect

| Module | compile classpath before | after |
|---|---|---|
| `mc-agent-tools-code` | 32 jars (whole runtime: spring-context, jackson, pebble, jtokkit, message repository, llm gateway, event bus …) | **7** |

Switched today: `mc-agent-tools-web`, `-gmail`, `-code`, `-document`,
`-web-browser`, `mc-vector-store-tools`. Still on the runtime: `mc-agent-tools`
(workspace), `-todo` (todo domain), `-workflow` (invokes agents).
