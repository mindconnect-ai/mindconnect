- **agents:** **a user can attach an account by signing in at the provider,
  not only by typing a password.** The second way to come by a
  [connection](#): `Acquisition.OAuth` on a `ConnectionSpec` puts a *Connect*
  button on the card, the browser goes to the provider's consent page and
  comes back with a stored connection. `OAuthFlow` does the
  authorization-code exchange with **PKCE by default** and sends a client
  secret only where a provider actually has one — an app registration several
  installations share cannot carry a secret, so it is a public client proving
  possession of a one-time verifier instead. The one-time `state` is generated
  per attempt, kept on the browser's session and consumed by the callback, so
  a callback that does not echo it is refused before the code is redeemed.
  `OAuthProvider` gets a repository (file, Postgres, in-memory) with the
  client secret encrypted at rest, and a token is renewed **on its way to a
  tool** — the one place every call passes and exactly when it is worth
  renewing. A refresh the provider refuses marks the connection `EXPIRED` with
  the reason rather than failing every call the same way, so the profile says
  what happened and the tool says to connect it again. Installations that
  register no OAuth app are unaffected; the callback lives at
  `/admin/oauth/callback` and has to be registered with the app.
