- **agents:** **a jar on the classpath declares what it brings, and a namespace can switch it off.**
  A module that ships tools, agents or a screen was loaded by whatever
  `ServiceLoader` found, with no record of what it brought and no way to keep
  it out of one namespace. It now carries a manifest,
  `META-INF/mindconnect/extension.json` — id, vendor, version, and under
  `contributes` its tools (as name patterns), agents, menu entries, routes,
  decorated ports and more. The host reads every manifest at start and the
  new **Extensions** screen (Install → Extensions) lists them; an admin
  switches an extension off for the namespace at hand, and its tools leave
  the catalogs and its sidebar entries the menu there. From a brand's own
  namespace the decision can be taken for the whole brand, locked against
  the namespaces' own say or not; `mindconnect.extensions.disabled` lets the
  operator switch an extension off everywhere. Decisions are stored per
  namespace (`system/extensions/` on files, `mc_extension_activation` in
  Postgres) and per brand (`system/extension-brands/`,
  `mc_extension_brand_activation`). Menu entries a manifest declares with a
  label and href are rendered by the host, into a shipped group or a new
  one; the routes it declares are sections of the admin UI — shell, layout
  and a 404 where the extension is off — so a jar's own screen needs no
  filter of its own; and beside every declared tool pattern and provider
  class the screen says whether the classpath actually has it. A worked
  example ships with the admin UI app, switched off: `mc-extension-demo`, a
  short adventure with the LLM as game master — a dice tool, an agent, a
  decorator around the trace store, a store of its own for scenarios, saved
  games and rolls, and two screens; switch it on under Install → Extensions. Problems — two manifests with one id, an unreadable one, two
  extensions replacing the same bean — and jars that bring providers without
  a manifest are logged and shown; `mindconnect.extensions.strict=true`
  refuses to start on them instead.
