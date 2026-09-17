- **agents:** **an attached file's tools reach the agent.** After `attachFile` (or an
  upload in the chat) the system note told the model to read the file with
  `vector_search`, but an agent whose definition did not list that tool never got
  it, so it could only answer that it had no way to look. A chat's attachments now
  bring what reads them — `vector_search` for what was indexed, `view_attachment`
  for an image or a PDF, `file_read` and `file_list` for a copy on disk — for as
  long as the file is attached, whether or not the definition lists them. Nothing
  else is granted this way, and what the installation disabled stays disabled.
