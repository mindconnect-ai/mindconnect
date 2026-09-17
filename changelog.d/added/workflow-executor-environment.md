- **workflow:** `WorkflowExecutorService.withEnvironment(supplier)` — where the
  built-in `env` variable comes from; the process environment plus system
  properties as before when not set. `WorkflowRunService` and
  `WorkflowAdminService` take the same supplier.
