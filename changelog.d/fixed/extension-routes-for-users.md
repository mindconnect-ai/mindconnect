- **agents:** **a route an extension's manifest opens to users is open to them.**
  `contributes.ui.routes[].roles` naming `USER` was shown on the Extensions
  screen but not enforced: the namespace access guard only knew its fixed list
  of open paths, so a plain user of the namespace was refused (403) a screen
  whose sidebar entry the same manifest showed them. The guard now lets a
  member of the namespace through on such a route while the extension is on
  there; where routes nest, the most specific one decides. Somebody who is not
  in the namespace is refused as before.
