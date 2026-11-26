# mc-llm-demo

How a library user calls a model, through either of two APIs and with either kind of
provider. One program for each:

| | our providers (`mc-llm-providers`) | a provider of your own (OpenAI Java SDK) |
|---|---|---|
| **`LlmChat`**, the gateway service | [`LlmChatWithOurProvidersDemo`](src/main/java/ai/mindconnect/llm/demo/LlmChatWithOurProvidersDemo.java) | [`LlmChatWithOwnProviderDemo`](src/main/java/ai/mindconnect/llm/demo/LlmChatWithOwnProviderDemo.java) |
| **`LlmGateway`**, one provider directly | [`LlmGatewayWithOurProviderDemo`](src/main/java/ai/mindconnect/llm/demo/LlmGatewayWithOurProviderDemo.java) | [`LlmGatewayWithOwnProviderDemo`](src/main/java/ai/mindconnect/llm/demo/LlmGatewayWithOwnProviderDemo.java) |

- **Through `LlmChat`** the caller only names a config. The service finds it in an
  `LlmConfigRepository`, follows aliases and fallbacks, resolves the secrets, picks the
  provider and retries rate limits.
- **Through `LlmGateway`** the caller holds the config itself, with model and key, and
  hands it to one provider with every request: no store, no routing, no retries.

Run any of them (a paid call to OpenAI). The key comes from the repository's `mc.env`,
as for the other demos (`McEnv`), unless `OPENAI_API_KEY` is set in the environment:

```bash
mvn -f llm/mc-llm-demo/pom.xml compile exec:java -Dexec.mainClass=ai.mindconnect.llm.demo.LlmChatWithOurProvidersDemo -Dexec.args="Name three rivers in Germany."
```

## 1. Through LlmChat, with our providers

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
    <artifactId>mc-jackson</artifactId>
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
ObjectMapper json = new JsonMapperFactory(List.of(new LlmJsonModule(), new JavaTimeModule())).create();

Map<LlmProvider, LlmGateway> providers = new EnumMap<>(LlmProvider.class);
providers.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json));
providers.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json));
providers.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json));
providers.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json));
providers.put(LlmProvider.LM_STUDIO, new OpenAiCompatibleGateway(http, json));

LlmConfigRepository configs = new InMemoryLlmConfigRepository(LlmScopeSupplier.fixed("demo"));
configs.save(LlmConfig.openAi("openai-mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"));
configs.save(LlmConfig.alias("agent-default", "openai-mini"));   // callers keep the name, the model may change

LlmChat chat = new RoutingLlmChatService(configs, new DefaultLlmGatewayRegistry(providers),
        LlmConfigResolver::resolved);           // ${VAR} placeholders from the process environment

chat.chatStreaming(LlmRequest.streaming("agent-default",
        List.of(LlmMessage.user("Name three rivers in Germany."))), chunk -> ...);
```

The caller knows the name of a config, nothing else: no provider, no model, no key.
The namespace is the store's: `LlmScopeSupplier.fixed` for one, a scope that answers per
call for a host that serves several. Only the providers you put into the map are
reachable; a config naming another provider fails at the call.
[`LlmGatewayConfiguration`](../mc-llm-gateway-app/src/main/java/ai/mindconnect/llm/gateway/app/LlmGatewayConfiguration.java)
wires the same for the REST server.

`LlmChatWithOurProvidersDemo` is this code as a program; `LlmGatewayWithOurProviderDemo`
calls `OpenAiResponsesGateway` alone, without the service.

## 2. Through LlmGateway, with a provider of your own

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

`LlmGatewayWithOwnProviderDemo` calls it directly with a config:

```java
LlmGateway gateway = new OpenAiSdkGateway();
gateway.chatStreaming(config, LlmRequest.streaming(config.name(), messages),
        chunk -> { if (chunk instanceof LlmStreamChunk.TextDelta d) System.out.print(d.text()); },
        Cancellation.none(), LlmCallListener.NOOP);
```

The same provider plugs into the gateway service, and callers again only name the
config. `LlmChatWithOwnProviderDemo` wires it with the configs in memory:

```java
LlmConfigRepository configs = new InMemoryLlmConfigRepository(LlmScopeSupplier.fixed("demo"));
configs.save(config);                                   // model, endpoint, key: ${VAR} placeholders allowed

LlmChat chat = new RoutingLlmChatService(configs,
        new DefaultLlmGatewayRegistry(Map.of(LlmProvider.OPENAI, new OpenAiSdkGateway())),
        LlmConfigResolver::resolved);                   // placeholders from the process environment

chat.chatStreaming(LlmRequest.streaming("agent-default", messages), chunk -> ...);
```

`OpenAiSdkGatewayTest` runs the provider against a local server that answers like
OpenAI: text, tool calls, a tool round trip and a rate limit, without network or cost.
[`LlmChatWithConfigInMemoryTest`](src/test/java/ai/mindconnect/llm/demo/LlmChatWithConfigInMemoryTest.java)
does the same through `LlmChat`.

## What this demo shows about the API

- A provider needs `mc-llm-provider-spi` only; the SDK brings okhttp, Jackson and
  Kotlin, and only to whoever picks it. (This module carries both ways, so it has both
  sets of dependencies; `OpenAiSdkGateway` itself imports nothing beyond the SPI and the SDK.)
- Our providers leave the JSON mapper to the caller: `mc-llm-jackson` and
  `jackson-datatype-jsr310` are dependencies of whoever wires them.
