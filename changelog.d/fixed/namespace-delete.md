- **agents:** **a deleted namespace stays deleted.** Deleting it while a variable or
  member change was being saved could bring the record back with its creator and
  variables, and switching into it worked again; the deletion now waits for such a
  write, and a write that waited for the deletion fails with `No namespace`. The task
  board no longer re-creates an empty `data/<namespace>/` for the finished tasks of a
  deleted namespace — they show without agent and owner. And a namespace created
  again under a deleted id gets its `.mc-partition.lock` back, so a second process
  on that partition is refused again.
