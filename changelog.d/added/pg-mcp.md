- **agents:** **on Postgres, MCP servers are kept in the database too.** With
  `mindconnect.persistence=postgres` the in-process MCP gateway kept its
  registrations and its discovered tools in files
  (`<namespace>/system/mcp-servers/`, `system/mcp-schema-cache/`) while
  everything else lived in tables. They are now rows of `mc_mcp_server` and
  `mc_mcp_schema_cache`, per namespace, holding the same JSON the files did
  (new module `mc-mcp-gateway-pg`, wired by the Postgres starter). Nothing to
  do on upgrade: the first time a namespace is used, its registration files
  are imported — once, remembered in `mc_mcp_server_import`, so a server
  deleted afterwards stays deleted — and the files are left in place. The
  discovery cache is not imported; each server is asked for its tools once
  more. File persistence is unchanged.
