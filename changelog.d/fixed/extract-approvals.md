- **agents:** **an approval question survives a restart when its parked call does.**
  With the task queue in Postgres (`mindconnect.task-queue.store=jdbc`,
  `TaskQueueFeature.jdbc()`) the open approval questions live in the table
  `mc_tool_approval` (`PgToolApprovalRepository`) instead of in memory, so a tool call
  parked before a restart can still be approved or denied after it.
