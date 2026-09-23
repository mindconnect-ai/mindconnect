- **agents:** **namespaces written by 0.8.2 keep their people after the upgrade.**
  Namespaces now list people by e-mail address, but a record 0.8.2 wrote names
  its creator and members by user id — and those entries matched nobody, so
  everyone lost access to their namespaces, with no way to repair it in the
  UI. An entry without an `@` is read as a user id again and matches the
  account that signs in with it. Likewise, `mindconnect.namespace-admins` set
  on an installation whose default namespace already had a record added
  nobody to it and locked everyone out; the named admins are now added to the
  existing record.
