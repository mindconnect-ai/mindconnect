- **agents:** **mail is a port now, and IMAP comes with it.** `mc-mail-core`
  has `MailStore` — folders, paging, search by sender, subject and date,
  reading, marking, moving, deleting, sending — and the `MailProvider` seam a
  kind of mailbox plugs into; `mc-mail-imap` brings IMAP, POP3 and SMTP, the
  Mailbox card on a user's profile and its Test button; `mc-agent-tools-mail`
  brings the tools: `mail_folders`, `mail_list`, `mail_read`, `mail_mark_read`,
  `mail_move`, `mail_delete`, `mail_send`. A call names its mailbox as
  `provider.key` (`email.work`) or asks `all`, so one set of tools serves every
  mailbox a user has; `MailAccounts` lists what the classpath can open, and a
  distribution that adds a provider (Outlook, Gmail) changes no tool, no name
  and no argument. Changing is a tool of its own, so an agent binding can make
  `mail_send` ask for approval while `mail_list` runs freely.
