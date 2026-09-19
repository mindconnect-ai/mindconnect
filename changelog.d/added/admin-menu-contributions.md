- **agents:** **a module can add its screen to the admin UI's sidebar.** A jar on
  the classpath that serves a page of its own had no way into the navigation —
  the menu was a fixed list, so the page was reachable by URL only. It now
  registers an `AdminMenuContribution` bean and says which entries to show,
  for an admin of the namespace and for a plain user of it separately, so a
  contribution never offers a viewer a route the server would refuse. The
  entries follow the shipped sections, before the Install group.
