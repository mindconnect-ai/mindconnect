- **agents:** **stored secrets are encrypted with AES-GCM instead of AES in
  ECB mode.** LLM keys, connection credentials, OAuth client secrets, vector
  store passwords and the variables of namespaces and users were encrypted
  without a nonce and without authentication: two secrets that began with the
  same 16 bytes began with the same stored bytes, and a stored value that had
  been changed was not noticed. Every value now gets a random nonce and an
  authentication tag, and is stored as `enc:v2:…`. Nothing has to be done on
  upgrade: values written before (`enc:…` without the `v2:`) are still read
  with the same `MINDCONNECT_ENCRYPTION_SECRET_KEY`, and the first start of
  this version rewrites them in the new form — in every namespace, and the
  log says how many records that were. A value the key cannot read is left as
  it is. A version before this one cannot read an `enc:v2:` value, so **back
  up the data directory or the database before the first start**: after it, a
  downgrade means entering every stored secret again.
