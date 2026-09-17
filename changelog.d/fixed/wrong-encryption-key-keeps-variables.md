- **agents:** **a wrong encryption key no longer deletes users' and namespaces'
  variables.** A stored value that did not decrypt was left out when the record was
  read and then saved back without it — on a user's next login, or when a namespace
  was renamed — so starting once with a wrong or rotated
  `MINDCONNECT_ENCRYPTION_SECRET_KEY` deleted them for good. Unreadable values are
  now kept as they are until their name is set again, and read again once the right
  key is back.
