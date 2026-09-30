- **agents:** **an unverified e-mail address no longer grants namespace roles.**
  Namespaces list people by address, and the address was taken from every
  sign-in, verified or not — somebody who changed their address at the
  identity provider to an admin's got that admin's namespaces. A token that
  says `email_verified: false` now records no address, and a verified address
  already on record stays. Tokens without the claim are still taken at their
  word, since some providers never send it; a provider that lets users change
  their address unchecked must send it (Keycloak does).
- **agents:** **inviting into a namespace no longer tells a stranger whether it
  exists or who is in it.** The admin check now comes first, and a missing
  namespace is refused in the same words as one the caller does not administer.
