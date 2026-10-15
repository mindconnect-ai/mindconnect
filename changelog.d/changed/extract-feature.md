- **agents:** **runtime features are features of a kernel.** The feature SPI moved out of
  `mc-agent-runtime-core` into the new module `common/mc-kernel` (package
  `ai.mindconnect.kernel`, JDK only): `RuntimeFeature` is `Feature`, `RuntimeBeans` is
  `Beans`, `RuntimeView` is `Kernel`; `ConfigurableFeature`, `FeatureContext`,
  `FeatureRegistry` and `FeatureException` keep their names. A feature reads the
  persistence and the mapper as beans — `ctx.require(Persistence.class)`,
  `ctx.require(ObjectMapper.class)` — and the kernel under construction as
  `ctx.kernel()`. A feature found on the classpath is listed in
  `META-INF/services/ai.mindconnect.kernel.Feature`.
