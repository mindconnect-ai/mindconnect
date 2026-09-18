---
id: namespaces-brand-hosts-and-no-access
area: namespaces
requires: [server-9090, keycloak, two-hosts, two-accounts]
duration: ~15 min
last-verified: never
---

# The address decides the namespace, and says so when it has none for you

**Goal:** A brand that owns a namespace binds every one of its hosts to it: the
work happens there whatever the caller chose before, a request naming another
namespace is refused, and somebody who is not in it is turned away — even when
they are in another one. Away from that address the namespace does not exist,
not in the switcher and not as a remembered choice. Whoever is listed somewhere
gets a namespace of their own **per brand**. Being turned away shows the page
that says so, wearing the host's brand, and its one button signs the caller out
and lands them at the identity provider's login.

## Preconditions

- An installation with **authentication on** and **two host names** pointing at
  it, one of them carrying a brand with a namespace (otherwise: SKIPPED):
  ```yaml
  mindconnect:
    branding:
      switch:
        acme:
          url-pattern: acme.example.test
          title: ACME AI
          namespace:
            creator: admin@example.com
  ```
  Locally: two entries in `/etc/hosts` for `127.0.0.1`, and the app on 9090.
- `MC_NAMESPACE_ADMINS` names the **admin** account, so the plain host's
  namespace is closed as well.
- Two accounts with e-mail addresses: the **admin** (in `MC_NAMESPACE_ADMINS`
  and the brand's `creator`) and a **guest** (in neither).
- `curl`, `jq`, a browser with two profiles.

## Setup

```bash
PLAIN=http://app.example.test:9090      # a host with no namespace of its own
BRAND=http://acme.example.test:9090     # the brand's host, namespace "acme"
c() { printf '%-46s %s\n' "$1" "$(curl -s -o /dev/null -m 10 -w '%{http_code}' "${@:2}")"; }
```

## Steps

1. Sign in as the admin on `$BRAND`.
   **Expected:** the chat, the header wearing the brand's name (**ACME AI**),
   and the namespace button reading **ACME AI**. The namespace was created by
   this first arrival: `$BRAND/admin/namespaces` shows it with the admin's
   address as `creator`.

2. Open the namespace switcher on `$BRAND`.
   **Expected:** the brand's namespace, and the admin's own namespace of this
   brand (`acme_<user>`), and nothing else — no `local`, no namespace of
   another brand. Both were made under this brand and are offered only here.

3. Sign in as the admin on `$PLAIN` (same browser).
   **Expected:** the shipped name in the header, the namespace button reading
   the default namespace. The switcher offers the default namespace and the
   admin's own of this host (`<user>`) — **not** `acme` and not `acme_<user>`.

4. **A remembered choice does not travel.** On `$BRAND`, switch to `acme_<user>`.
   Then reload `$PLAIN`.
   **Expected:** `$PLAIN` is still in its own namespace; the choice made under
   the brand did not come along. Reload `$BRAND`: it is `acme_<user>` again.

5. **Naming another namespace is refused**, whichever way it is named:
   ```bash
   T=<an API token of the admin>
   c "acme from the plain host, by header" -H "Authorization: Bearer $T" \
      -H 'X-Mindconnect-Namespace: acme' $PLAIN/api/agents
   c "acme from the plain host, by path"   -H "Authorization: Bearer $T" $PLAIN/ns/acme/api/agents
   c "the default one from the brand host" -H "Authorization: Bearer $T" \
      -H 'X-Mindconnect-Namespace: local' $BRAND/api/agents
   c "acme from its own host"              -H "Authorization: Bearer $T" $BRAND/api/agents
   ```
   **Expected:** `403`, `403`, `403`, `200`. The first two say
   `This address does not serve namespace 'acme'`, the third the same for the
   default one.

6. **Somebody who is in another namespace is still turned away here.** Sign in
   as the guest on `$PLAIN` — they are in nothing, so this is the first turn-away.
   **Expected:** the page at `$PLAIN/no-access`: the host's name and mark, the
   line **Sorry — you are not registered here.**, what to do about it, and the
   button **Sign in with another account**. Not a blank error page, and not the
   app.

7. As the admin on `$PLAIN`, invite the guest into the default namespace. Then
   reload the guest's browser.
   **Expected:** the guest reaches the chat on `$PLAIN`.

8. Now send the guest to `$BRAND`.
   **Expected:** the page again — the brand's namespace is not theirs, and
   being in another one is no way in. This time the page wears the **brand's**
   name, mark and stylesheet.

9. **The page itself always answers**, for anybody:
   ```bash
   c "the page, signed out"   $BRAND/no-access
   c "its stylesheet"         $BRAND/branding/erni.css   # or the brand's own
   c "the way out"            $BRAND/admin/logout
   ```
   **Expected:** `403` for the page (it is an answer, not a pretence that all
   is well) with the branded HTML as its body, `200` for the stylesheet, `302`
   for the logout. None of them is the container's blank error page.

10. **The button does what it says.** In the guest's browser, press **Sign in
    with another account**.
    **Expected:** they are signed out at the identity provider and land on the
    provider's own login form — not on the app's "Sign in with Keycloak"
    landing page. Signing in there as the admin lands in the brand's namespace.

11. **Nobody was let in by being sent away.** As the admin on `$BRAND`, open
    `$BRAND/admin/namespaces`.
    **Expected:** the guest's address is in no list; arriving under a host
    makes nobody a member.

## Cleanup

```bash
# remove the guest from the default namespace again, revoke the token of step 5
```
