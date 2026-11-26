# mc-llm-demo

How a library user calls a model through the LLM gateway, in two ways:

1. **With our providers.** You take the gateway and the providers we ship (OpenAI,
   Azure, Claude, Gemini, …) and only name a config.
2. **With a provider of your own.** You implement the provider SPI yourself; here with
   the official OpenAI Java SDK (`com.openai:openai-java`).

Both ways run against the same gateway service, `mc-llm-gateway`: it finds the config
by name, follows aliases and fallbacks, resolves the secrets and retries rate limits.

## 1. With our providers

```xml
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-llm-gateway</artifactId>
</dependency>
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-llm-providers</artifactId>
</dependency>
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-llm-jackson</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

```java
OkHttpClient http = new OkHttpClient();
ObjectMapper json = JsonMapper.builder()
        .addModule(new LlmJsonModule()).addModule(new JavaTimeModule()).build();

Map<LlmProvider, LlmGateway> providers = new EnumMap<>(LlmProvider.class);
providers.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json));
providers.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json));
providers.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json));
providers.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json));
providers.put(LlmProvider.LM_STUDIO, new OpenAiCompatibleGateway(http, json));

LlmConfigRepository configs = new InMemoryLlmConfigRepository(TenantScope.fixed("demo"));
configs.save(LlmConfig.openAi("openai-mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"));
configs.save(LlmConfig.alias("agent-default", "openai-mini"));   // callers keep the name, the model may change

LlmChat chat = new RoutingLlmChatService(configs, new DefaultLlmGatewayRegistry(providers),
        LlmConfigResolver::resolved);           // ${VAR} placeholders from the process environment

chat.chatStreaming(LlmRequest.streaming("agent-default",
        List.of(LlmMessage.user("Name three rivers in Germany."))), chunk -> ...);
```

The caller knows the name of a config, nothing else: no provider, no model, no key.
The namespace is the store's: `TenantScope.fixed` for one, a scope that answers per
call for a host that serves several. Only the providers you put into the map are
reachable; a config naming another provider fails at the call.
[`LlmGatewayConfiguration`](../mc-llm-gateway-app/src/main/java/ai/mindconnect/llm/gateway/app/LlmGatewayConfiguration.java)
wires the same for the REST server.

[`OurProvidersDemo`](src/main/java/ai/mindconnect/llm/demo/OurProvidersDemo.java) is this
code as a program. Run it (a paid call to OpenAI):

```bash
OPENAI_API_KEY=... mvn -f llm/mc-llm-demo/pom.xml compile exec:java -Dexec.mainClass=ai.mindconnect.llm.demo.OurProvidersDemo -Dexec.args="Name three rivers in Germany."
```

## 2. With a provider of your own

```xml
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-llm-provider-spi</artifactId>
</dependency>
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java</artifactId>
</dependency>
```

[`OpenAiSdkGateway`](src/main/java/ai/mindconnect/llm/demo/adapter/openaisdk/OpenAiSdkGateway.java)
implements `LlmGateway`: it turns an `LlmRequest` into the SDK's
`ChatCompletionCreateParams`, streams the answer back as `LlmStreamChunk`s, reassembles
tool calls for the `LlmCallEvent` and turns rate limits and server errors into
`LlmTransientException`. The SDK's own retries are off, because retrying is the
gateway's job.

[`OwnProviderDemo`](src/main/java/ai/mindconnect/llm/demo/OwnProviderDemo.java) calls it
directly with a config:

```java
LlmGateway gateway = new OpenAiSdkGateway();
gateway.chatStreaming(config, LlmRequest.streaming(config.name(), messages),
        chunk -> { if (chunk instanceof LlmStreamChunk.TextDelta d) System.out.print(d.text()); },
        Cancellation.none(), LlmCallListener.NOOP);
```

The same provider plugs into the gateway service, and callers again only name the
config. [`LlmChatWithConfigInMemoryTest`](src/test/java/ai/mindconnect/llm/demo/LlmChatWithConfigInMemoryTest.java)
wires it with the configs in memory:

```java
LlmConfigRepository configs = new InMemoryLlmConfigRepository(TenantScope.fixed("demo"));
configs.save(config);                                   // model, endpoint, key: ${VAR} placeholders allowed

LlmChat chat = new RoutingLlmChatService(configs,
        new DefaultLlmGatewayRegistry(Map.of(LlmProvider.OPENAI, new OpenAiSdkGateway())),
        LlmConfigResolver::resolved);                   // placeholders from the process environment

chat.chatStreaming(LlmRequest.streaming("agent-default", messages), chunk -> ...);
```

Run it (a paid call to OpenAI):

```bash
OPENAI_API_KEY=... mvn -f llm/mc-llm-demo/pom.xml compile exec:java -Dexec.mainClass=ai.mindconnect.llm.demo.OwnProviderDemo -Dexec.args="Name three rivers in Germany."
```

`OpenAiSdkGatewayTest` runs the provider against a local server that answers like
OpenAI: text, tool calls, a tool round trip and a rate limit, without network or cost.
`LlmChatWithConfigInMemoryTest` does the same through `LlmChat`.

## What this demo shows about the API

- A provider needs `mc-llm-provider-spi` only; the SDK brings okhttp, Jackson and
  Kotlin, and only to whoever picks it. (This module carries both ways, so it has both
  sets of dependencies; `OpenAiSdkGateway` itself imports nothing beyond the SPI and the SDK.)
- Our providers leave the JSON mapper to the caller: `mc-llm-jackson` and
  `jackson-datatype-jsr310` are dependencies of whoever wires them.
