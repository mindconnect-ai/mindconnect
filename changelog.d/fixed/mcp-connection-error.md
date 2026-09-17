- **agents:** **Test connection names the MCP server it could not reach.** A url whose
  host does not resolve showed only `java.nio.channels.UnresolvedAddressException` —
  no host, no sentence. The result now reads "MCP initialize failed for endpoint
  https://…: …" with the cause's name after it.
