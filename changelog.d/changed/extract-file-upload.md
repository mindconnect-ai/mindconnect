- **agents:** **file upload lives in `agents/features/file-upload/`.** The module
  `mc-agent-runtime-feature-file-upload` is `mc-agent-file-upload`, with
  `FileUploadFeature` and `AttachSupport` in `ai.mindconnect.agent.fileupload.feature`
  (formerly `ai.mindconnect.agent.runtime.feature.fileupload`). The `view_attachment` tool
  left `mc-agent-runtime-core` for `mc-agent-file-upload-core`: `ViewAttachmentTool` and its
  factory are in `ai.mindconnect.agent.fileupload` (formerly
  `ai.mindconnect.agent.runtime.tools.attachment`); the runtime core names the tool as
  `AttachedFile.VIEW_TOOL`.
