- **agents:** **moving, deleting and undoing mail works on IMAP servers without MOVE or UIDPLUS.**
  `mail_move`, `mail_delete` (a move to the wastebasket) and Undo failed outright on such
  servers, because the IMAP client only sends MOVE when the server announces it and never
  falls back. The mailbox now checks what the server offers and copies, flags and expunges
  where there is no MOVE — expunging only the moved message, not others another client
  flagged deleted.
- **agents:** **`mail_list` no longer lists mail that was deleted, moved or marked read.** The
  actions now write through to the window index; before, an older message deleted with
  `mail_delete` stayed in `mail_list` for good. A page read far below the window no longer
  lands in the window at the wrong place, and concurrent writes to the index and view
  files no longer lose each other's changes.
- **agents:** **`mail_list_remove` takes out only the message meant.** The same id can name
  different messages in different folders or mailboxes; a bare id that names more than
  one entry is now refused with the candidates, and messages can be named with account
  and folder.
- **agents:** **IMAP message ids carry the folder's UIDVALIDITY** (`1712345678-42`). An id
  from before a server renumbered a folder now names nothing instead of whichever message
  has that UID now, and a window whose ids were all renamed is filled again. Bare UIDs from
  before this release are still accepted.
