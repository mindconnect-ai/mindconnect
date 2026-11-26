# mc-llm-demo

How a library user calls a model through the LLM gateway, in two ways:

1. **With our providers.** You take the gateway and the providers we ship (OpenAI,
   Azure, Claude, Gemini, …) and only name a config.
2. **With a provider of your own.** You implement the provider SPI yourself; here with
   the official OpenAI Java SDK (`com.openai:openai-java`).

The second way runs today. The first needs `mc-llm-gateway` and `mc-llm-providers`,
which still live in `agents/` and move to `llm/` next; until then it is shown here as
the code it will be.

## 1. With our providers (after the gateway and the providers move here)

```xml
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-llm-gateway</artifactId>
</dependency>
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-llm-providers</artifactId>
</dependency>
```

```java
LlmChat chat = LlmGatewayService.builder()
        .providers(OpenAiProviders.all())                  // our providers, one per LlmProvider
        .config(LlmConfig.openAi("agent-default", "gpt-5.4-mini", "${OPENAI_API_KEY}"))
        .build();                                          // configs in memory, secrets from the environment

LlmResponse answer = chat.chat("local",
        LlmRequest.of("agent-default", List.of(LlmMessage.user("Name three rivers in Germany."))));
```

The caller knows a namespace and the name of a config, nothing else: no provider, no
model, no key. The builder's names are the plan, not yet code.

## 2. With a provider of your own (runs today)

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

Once the gateway service is in `llm/`, the same provider plugs into it, and callers
again only name the config:

```java
LlmChat chat = LlmGatewayService.builder()
        .provider(LlmProvider.OPENAI, new OpenAiSdkGateway())
        .config(...)
        .build();
```

Run it (a paid call to OpenAI):

```bash
OPENAI_API_KEY=... mvn -f llm/mc-llm-demo/pom.xml compile exec:java -Dexec.mainClass=ai.mindconnect.llm.demo.OwnProviderDemo -Dexec.args="Name three rivers in Germany."
```

`OpenAiSdkGatewayTest` runs the provider against a local server that answers like
OpenAI: text, tool calls, a tool round trip and a rate limit, without network or cost.

## What this demo shows about the API

- A provider needs `mc-llm-provider-spi` only; the SDK brings okhttp, Jackson and
  Kotlin, and only to whoever picks it.
