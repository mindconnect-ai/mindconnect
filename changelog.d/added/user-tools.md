- **agents:** **a user can put tools in their own account, and the same tool
  twice.** The chain of who may say what about a tool ran Source →
  Installation → Namespace → Agent and stopped there; `UserTool` is the step
  below it. One record does both things a person wants: a binding for a tool
  the agent does not list **adds** it to their chats, one for a tool it does
  **changes** it for them alone — its name, its description, which of their
  connections it runs on, whether it asks first, or whether they want it at
  all. Added under a name of their own it becomes a second entry, so
  `email_privat` and `email_arbeit` can sit side by side, one mailbox each;
  underneath they are ordinary `AliasTool` and `PinnedParamsTool` overrides,
  the same thing an operator could write into an agent definition by hand —
  only derived from *their* connections instead of typed into a definition
  several people share. It reaches **the agent they are chatting with and no
  sub-agent**: a sub-agent's roster was curated for a narrow job. Their
  *connections* do follow them everywhere, because the call scope carries the
  user all the way down. Two rules the layer keeps: a name is claimed once,
  and an approval is tightened by any layer and relaxed by none — a user
  cannot switch on what the installation switched off, nor ask for fewer
  questions than their agent asks for. `UserToolRepository` has file, Postgres
  and in-memory adapters; the profile has a **My tools** tab. A host that
  keeps no per-user tools is unaffected.
