- **agents:** **the workflow feature lives in `agents/features/workflow/`.**
  `mc-agent-runtime-feature-workflows` is `mc-agent-workflow`, and `WorkflowsFeature`
  moved from `ai.mindconnect.agent.runtime.feature.workflows` to
  `ai.mindconnect.agent.workflow.feature`; an embedding that installs it imports it
  from there and depends on the new artifact. `mc-agent-tools-workflow` moved beside it
  from `tools/core/`; its artifact and packages stay.
