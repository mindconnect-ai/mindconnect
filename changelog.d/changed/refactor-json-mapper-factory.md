- **Agents — every ObjectMapper comes from the runtime.** The runtime builds its JSON mapper from
  the Jackson modules its plugins contribute; a module is no longer picked up from
  `META-INF/services/com.fasterxml.jackson.databind.Module`. A plugin contributes one with
  `registry.contribute(Module.class, …)`, and a class that needs a mapper takes the
  `JsonMapperFactory` in its constructor. With the Spring starter, Spring's `ObjectMapper` is the
  runtime's: an application that defined its own `ObjectMapper` bean defines a
  `JsonMapperCustomizer` bean instead. `SpiWorkflowContextFactory.create` takes the
  `JsonMapperFactory`. The ids and `Namespace` carry no Jackson annotations any more; how they
  stand in JSON is unchanged and lives in `mc-agent-jackson`. The user, namespace and extension
  services are runtime plugins of their own modules (`UserPlugin`, `NamespacePlugin`,
  `ExtensionPlugin` and their file and Postgres variants).
