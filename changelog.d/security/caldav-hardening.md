- **agents:** **a CalDAV calendar id can no longer send the account's password
  to another server.** The calendar tools took the calendar id the model
  passed as the URL to call, and every request carries the account's Basic
  credentials — a prompt injected through a mail or a web page could have the
  model name `https://attacker.example/` as calendar and collect them. An id is
  now only followed when it is a calendar the server listed or lies under the
  account's address on the same scheme, host and port. Plain `http://`
  addresses are accepted only for a server on the same machine or the local
  network (`localhost`, private addresses, `nas`, `*.local`); anything else
  has to be `https://`.
  A redirect from the calendar server is followed only on the same server
  as well; the HTTP client used to carry the password along to wherever a
  redirect pointed.
