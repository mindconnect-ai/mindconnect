- **agents:** **what a user's screens remember has a place of its own.**
  `Preferences`, `PreferenceRepository` (file and Postgres, `mc_user_preference`)
  and `PreferenceService` keep one small document per user and *scope*
  (`office.email`, `admin.agents`) — the folder last open, the view last chosen,
  a column last sorted by. A value is a string; changing one is a merge, and a
  write that changes nothing writes nothing, so a screen can store where it is
  on every navigation. Scopes, keys and sizes are bounded (64-character names,
  100 keys, 4,096 characters a value). Preferences are not the user's variables:
  they are not encrypted, not handed to tools and not listed under Variables —
  which is what screens storing their state as variables had been doing. A host
  that assembles no `PreferenceRepository` has no `PreferenceService`, and a
  screen that asks for one simply starts where it always did.
