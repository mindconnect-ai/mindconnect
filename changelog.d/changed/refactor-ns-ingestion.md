- **agents:** **an ingestion workflow runs on the workflow engine directly.**
  Uploads into a vector store (chat attachments, the REST API, the embedded
  runtime) start the store's ingestion workflow through `WorkflowExecutorService`
  instead of the workflow admin's run service. An ingestion workflow that halts
  is reported as not finished and no longer kept as a resumable run.
  `mc-agent-api-rest` no longer brings `mc-workflow-admin-rest` along: a host that
  serves `/api/workflows` declares it itself, as `mc-agent-api-app` now does.
