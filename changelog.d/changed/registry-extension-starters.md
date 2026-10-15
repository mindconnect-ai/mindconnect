- **agents:** **the registry's and the extensions' Spring wiring moved into starters of their own.**
  `mc-agent-registry` and `mc-extension` are plain libraries now; a Spring Boot server
  that used to get `RegistryAutoConfiguration` or `ExtensionAutoConfiguration` from them
  adds `mc-agent-starter-registry` and `mc-agent-starter-extension` instead (the shipped
  apps do). `ExtensionAudit` moved from `ai.mindconnect.extension.spring` to
  `ai.mindconnect.extension.adapter.classpath`.
