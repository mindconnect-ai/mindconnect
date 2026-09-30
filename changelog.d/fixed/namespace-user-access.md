- **agents:** **a user of a namespace reaches their own connections, tools and
  notifications again.** The namespace access check let non-admins through to
  the chat and the profile but answered 403 on the profile's Connections and
  Tools tabs, the sign-in at a provider that attaches an account, and the
  notification bell. These routes only ever act on the caller's own records
  and are now open to every member.
- **agents:** **an agent's pinned tool values hold when a user adds the same
  tool to their account.** A user binding used to override the agent's pins on
  the same key; now the agent's pins win and the user's values fill only what
  it left open. A user binding that would put a different tool under a name
  the agent already uses is ignored, so the agent's pins and approval stay with
  the tool it chose. Adding a tool under a name of one's own is unchanged.
