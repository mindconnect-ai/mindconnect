---
id: connections-two-mailboxes-two-tools
area: connections
requires: [server-9090, mc-agent-tools-email, two-mailboxes]
duration: ~15 min
last-verified: never (written 2026-09-19 with the feature; the automated half is TwoMailboxesIntegrationTest in mc-commercial)
---

# Two mailboxes, two tools, one tool name

**Goal:** A user connects two mailboxes of their own, adds the same mail tool
twice — one entry per mailbox — and the agent reads the right one under each
name, without the agent definition knowing anything about either account.

**How it is made observable:** two mailboxes with a distinguishable message in
each. The chat is asked to list each one by the name of the tool entry, and the
subjects show which mailbox was read.

**What this is really checking:** that a personal account never has to be
written into a shared agent definition. Nothing in the agent, the namespace or
the installation names "arbeit" — only this user's own two records do.

## Preconditions

- Admin UI running at http://localhost:9090, authentication off (dev user
  `mc_user`) (otherwise: SKIPPED)
- `mc-agent-tools-email` from `mc-commercial` on the classpath — the tool
  catalogue lists `email_list_messages` (otherwise: SKIPPED)
- **Two mailboxes you may read**, with their IMAP host, account and password
  at hand. Two accounts at the same provider are fine (otherwise: SKIPPED)
- One message in each whose subject you will recognise, and which is *not* in
  the other
- An agent to chat with that has **no** mail tool of its own — the default
  assistant will do. That it has none is the point: the tools come from the
  user, not from the definition

## Steps

### 1. Connect the first mailbox

http://localhost:9090/admin/profile → tab **Connections**.

The card **Mailbox** is there, with *Add manually*. Click it, fill in name
`Privat`, the server, the account and the password, save.

- **Expect:** the row appears with *Referred to as* `privat`, status
  `connected`, and the name marked `(default)` — the first one is.
- **Expect:** re-opening it with *Edit* shows the server and account, and an
  **empty** password field. A secret is never shown back.

### 2. Connect the second

Same card, *Add manually*, name `Arbeit`, the other mailbox.

- **Expect:** two rows. `privat` still carries `(default)`; `arbeit` does not.

### 3. The chat offers a choice, not a guess

Open a chat with the agent and ask it to list your mail.

- **Expect:** it either reads the **Privat** mailbox (the default) or asks
  which one you mean. It must not invent a third name.
- **Expect:** asking it explicitly for the work mailbox reads the other one.

*(If the agent has no mail tool at all, this step is SKIPPED — it starts
working from step 5.)*

### 4. Add the tool twice, one mailbox each

Profile → tab **My tools** → *Add tool…*

1. Tool `email_list_messages` → **Continue**. Name in your chats:
   `email_privat`, what to tell the model: *Reads my private mailbox*,
   account: **Privat**. Add.
2. The same again: `email_arbeit`, *Reads my work mailbox*, account: **Arbeit**.

- **Expect:** two rows, both with tool `email_list_messages`, accounts *Privat*
  and *Arbeit*, status `on`.
- **Expect:** trying to add a third under the name `email_arbeit` is refused
  with the reason. A name is claimed once.

### 5. The agent has both, and they read different mailboxes

Start a **new** chat (the tools are resolved per turn, but a fresh chat removes
any doubt) and ask: *"Which mail tools do you have?"*

- **Expect:** it names `email_privat` and `email_arbeit` — and it is the same
  agent as in step 3, whose definition still lists neither.

Ask: *"List the newest messages in my private mailbox."*

- **Expect:** the subject you put in the **Privat** mailbox, and **not** the
  one from Arbeit.

Ask: *"And now the work one."*

- **Expect:** the other subject, and not the private one.

### 6. The account cannot be talked out of it

Ask: *"Use email_arbeit but read the private mailbox."*

- **Expect:** it reads the **work** mailbox anyway, or says it cannot choose.
  The account is pinned and is not offered to the model as a parameter.

### 7. Moving the default moves what is unpinned

Profile → **Connections** → row `Arbeit` → *Make default*.

- **Expect:** `(default)` moves to Arbeit.
- **Expect:** in a new chat, a bare "list my mail" now reads **Arbeit** — but
  `email_privat` still reads Privat. A pin is a decision; the default is a
  fallback.

### 8. Switching one off takes it out of the chat

Profile → **My tools** → row `email_arbeit` → *On / off*.

- **Expect:** status `off`, and in a new chat the agent no longer names
  `email_arbeit`. `email_privat` is still there.

### 9. A sub-agent does not inherit them

Ask the agent to delegate something to a sub-agent (any agent in its roster).

- **Expect:** the sub-agent does **not** have `email_privat` or
  `email_arbeit`. A sub-agent keeps the tools its own definition gives it.

*(SKIPPED when the agent delegates to nobody.)*

### 10. Removing a connection is noticed

Profile → **Connections** → row `Privat` → *Remove*, confirm.

- **Expect:** `arbeit` remains and is the default.
- **Expect:** in a new chat, calling `email_privat` answers with an error that
  names the connection it cannot find and lists the ones you do have — not a
  stack trace, and not somebody else's mailbox.

## Cleanup

Profile → **My tools**: remove both entries. **Connections**: remove what is
left. The notification about an unconnected mailbox may reappear on the next
sign-in; that is the check doing its job.
