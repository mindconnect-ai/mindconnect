- **agents:** **the chat's Tools badge agrees with the Tools picker.** The badge on
  the "+" menu's **Tools** entry counted every tool the chat binds, while the
  picker's header counts the tools that are on and have a row. A fresh
  `default-chat` read `19` on the menu and `12 on` in the picker: the badge also
  counted the tools set to Search and the `gmail_*` tools a host without Gmail
  credentials cannot resolve. Both numbers now come from one count — tools on and
  resolvable here — on the chat page and after every redraw of the composer.
