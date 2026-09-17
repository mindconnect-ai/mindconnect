- **agents:** **an approved tool call stays in the chat.** Answering an approval
  card rebuilt the whole message list, which wiped the card of the tool that had
  just started running; it only came back when the turn ended. Now only the
  approval card is removed.
