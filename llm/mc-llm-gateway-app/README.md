# mc-llm-gateway-app

The LLM gateway as a REST server: chat with a model by the name of its config, and
manage configs and prices. Everything lives under a namespace,
`/api/ns/{namespace}/…`; configs and prices live in Postgres, tables created on start.
No security: run it only where nobody else can reach it.

```bash
podman run -d --name mc-llm-gateway-pg -p 5435:5432 -e POSTGRES_PASSWORD=gateway -e POSTGRES_DB=llm_gateway postgres:17
mvn -f llm/mc-llm-gateway-app/pom.xml spring-boot:run     # port 8095, MC_LLM_GATEWAY_PORT changes it
```

`MC_LLM_GATEWAY_DB_URL`, `_USER` and `_PASSWORD` point it at another database;
`mindconnect.llm.store=memory` runs it without one.

On start the namespace `default` (`mindconnect.llm.seed-namespace`) gets three OpenAI
configs, unless one of that name is there already: `openai-chat` (`gpt-5.4-mini`),
`openai-embedding` (`text-embedding-3-small`) and `openai-transcription`
(`gpt-4o-mini-transcribe`). Their key is `${OPENAI_API_KEY}`, resolved from the
environment per call. Only chat is reachable over REST so far.

Swagger UI: <http://localhost:8095/swagger-ui.html>, the OpenAPI description at
`/v3/api-docs`.

| Path | What it does |
|------|--------------|
| `POST /api/ns/{ns}/chat` | chat and wait for the whole answer (`LlmResponse`) |
| `POST /api/ns/{ns}/chat/stream` | the same as server-sent events, one per chunk, named after its type |
| `GET/POST /api/ns/{ns}/configs`, `GET/DELETE …/configs/{name}` | list, create or replace, read, delete configs |
| `GET/POST /api/ns/{ns}/prices`, `DELETE …/prices/{id}` | list (`?config=`), create or replace, delete price periods |

`defaultTemperature` and `maxOutputTokens` are numbers in the config, not optional:
left out, they are 0. A config read back shows its key as stored. Give it as a placeholder such as
`${OPENAI_API_KEY}`: it resolves in the server's environment at call time.

```bash
curl -s localhost:8095/api/ns/demo/configs -H 'Content-Type: application/json' -d '{
  "name": "chat", "provider": "OPENAI", "model": "gpt-5.4-mini",
  "baseUrl": "https://api.openai.com", "apiKey": "${OPENAI_API_KEY}",
  "defaultTemperature": 1.0, "maxOutputTokens": 4096}'

curl -s localhost:8095/api/ns/demo/chat -H 'Content-Type: application/json' -d '{
  "configName": "chat", "messages": [{"role": "USER", "content": "Say hello"}]}'
```

For now the app depends on `agents/`: the gateway service and the providers move to
`llm/` in steps 2 and 3 of the gateway's move. That is also why the module is listed
in the root POM and not in `llm/pom.xml`, whose ArchUnit rules allow no other area.
