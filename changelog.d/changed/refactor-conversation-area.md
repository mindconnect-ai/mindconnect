- The conversation area no longer depends on `agents`: its stores take a
  `TenantScope` instead of a `ScopeSupplier`, and `conversation/pom.xml` builds
  the area on its own.
- The conversation modules are cut by what they hold: `mc-conversation-api`
  (was `mc-conversation-core`) has only the model and the ports,
  `mc-conversation` the `ConversationService` and the stores in memory, and the
  new `mc-conversation-file` the store on files. The tests every store passes
  are `ConversationStoreContract` in the test-jar of `mc-conversation-api`.
