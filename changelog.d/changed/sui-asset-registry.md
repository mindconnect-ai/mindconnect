- **agents:** **the Admin UI installs its semantic-ui extensions from the asset registry (semantic-ui 0.4.0).**
  `app.js` no longer imports a fixed list (jsonviewer, markdown, diagram); it
  calls `installAll(renderer, bus)` from `/sui/assets.js`, so any jar on the
  classpath that declares its scripts and stylesheets in
  `META-INF/sui/assets.json` — a module's calendar or charts, a plugin's
  widgets — is on the page from the first render, with no filter or loader
  script of its own. The registry's stylesheets are written into the shell's
  head ahead of the branding stylesheets, which still win. A host that ships
  its own `index.html`/`app.js` should switch to `installAll` too. The 0.4.0
  update also means an iframe with `sandbox("")` is now really sandboxed and
  RICHTEXT values are sanitised wherever they are rendered.
