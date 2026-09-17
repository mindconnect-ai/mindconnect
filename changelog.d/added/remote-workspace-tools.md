- **agents:** **the file tools, the document tools, `bash`, `process_list`, `process_kill` and
  `code_execute` can work on a virtual environment server instead of the agent's machine.**
  Add `mc-agent-tools-virtual-env` (the admin UI and API apps ship it) and set
  `mindconnect.virtual-env.client.url` (`MC_VIRTUAL_ENV_URL` in the `server` profile). Each chat
  then gets a workspace of its own there, mounted at `/workspace` in its container; its uploads
  are copied in, the session directory the prompt names is read as `/workspace`, and `bash` runs
  in the container — so the `server` profile can offer it (`MC_TOOLS_DISABLED=`). A binding picks
  its template with the override `{"environment": "office"}`. Tools and schemas are unchanged;
  tool code reaches the disk through the new `WorkspaceFiles` port in `mc-agent-tool-spi`, and a
  `WorkspaceProvider` bean decides where. `vector_ingest_file` stays on the agent's machine.
- **agents:** **calls to a virtual environment server carry a token signed for the chat's user.**
  With `mindconnect.virtual-env.client.issuer` (`MC_VIRTUAL_ENV_ISSUER`) every call carries a
  short-lived RS256 token for the calling user and namespace, and the agent apps publish the
  public key at `/.well-known/mc-virtual-env/jwks.json`. The key pair exists only in memory and is
  new on every start; a server that trusts the issuer tells users apart without any shared secret.
