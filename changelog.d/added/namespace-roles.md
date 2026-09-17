- **agents:** **a namespace now says who shapes it and who works in it.** Its
  people are two lists of e-mail addresses — `admins` and `users` — instead of
  one flat membership. Admins create and change what the namespace holds,
  invite, promote and set its variables; users work in it. The one right an
  admin cannot be given is deleting the namespace: that stays with whoever
  created it, who is also the one entry that cannot be taken out of the admins.
  Because the lists are addresses and not accounts, somebody can be invited
  **before they have ever signed in** — the membership is simply waiting at
  their first sign-in, with nothing to send and nothing pending. A name without
  an `@` gets `mindconnect.email-domain` appended, so a company whose accounts
  are all `<name>@company.example` invites by name. Records written before this
  read as they did: their members become users, their creator stays an admin.

- **agents:** **a brand can bring its own namespace.**
  `mindconnect.branding.switch.<brand>.namespace` names the admins its hosts
  work under (`creator: <address>` for a single one); the namespace's id is the
  brand's own name unless `id` says otherwise, and its display name is the
  brand's title. It is created the first time somebody arrives under one of
  that brand's hosts — and never changed from configuration afterwards: who is
  in a namespace is its admins' business, not that of a file edited later.

- **agents:** **signing in is no longer the same as being let in.**
  `mindconnect.namespace-admins` names who shapes the default namespace, and
  naming anybody closes it: an account this installation lists nowhere now sees
  a page saying so, with the way to sign out, instead of an empty app — and the
  API answers 403 rather than falling back into the default namespace. Nobody
  is put into a namespace by arriving under a host; an admin invites them.
  Whoever is listed somewhere also gets an empty namespace of their own, and
  the first sign-in under a brand opens that brand's work. Left unset,
  `namespace-admins` keeps the default namespace open to every signed-in user,
  so a single-user installation and the dev mode are exactly as they were.
