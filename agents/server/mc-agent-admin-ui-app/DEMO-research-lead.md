# Demo: Paralleler Multi-Agent-Research-Report

Diese Demo zeigt die drei Kernfähigkeiten der Orchestrierungs-Architektur:
**Zerlegung (todo)** → **paralleler Fan-out (`run_agents`)** → **Verifikation + Synthese**.

Der Demo-Agent heißt **`research-lead`**. Er recherchiert eine mehrteilige Frage,
indem er die Sub-Topics gleichzeitig an `web-researcher` delegiert, die Kernaussagen
vom `verifier` gegenchecken lässt und einen zitierten Markdown-Report nach
`report.md` ins Session-Workspace schreibt.

---

## Vorbereitung (einmalig, vor der Demo)

1. **Build & Start** der `admin-ui-app` aus IntelliJ (deine Run-Config hat den
   `TAVILY_API_KEY` im Environment — der wird für `web_search`/`web_read` gebraucht).
   Beim Start importiert der `InitialDataLoader` die neuen Agenten automatisch:
   `research-lead`, `planner`, `explorer`, `code-analyst`, `document-analyst`, `verifier`.

2. **Modell prüfen (WICHTIG).** `research-lead` nutzt `llmConfigName: "agent-default"`,
   und `agent-default` zeigt aktuell auf **LM_STUDIO** (lokal). Komplexe Orchestrierung
   mit striktem Tool-Calling ist mit einem starken Modell deutlich zuverlässiger.
   Für die Demo empfohlen: in der Admin-UI das LLM-Config `agent-default` temporär auf
   ein starkes Modell zeigen lassen (Claude/OpenAI), ODER in `research-lead.json`
   `"llmConfigName"` auf `"claude-default"` bzw. `"openai-default"` setzen und neu starten.
   → Probier den Ablauf EINMAL vorher durch, bevor du live gehst.

3. **Smoke-Test**: Öffne einen Chat mit `research-lead`, tippe den Hauptprompt (unten),
   und schau dass (a) ein todo-Plan erscheint, (b) mehrere `web-researcher`-Sub-Agenten
   PARALLEL starten, (c) `report.md` im Workspace landet.

---

## Hauptprompt (der "Hero"-Case)

Öffne einen neuen Chat mit dem Agenten **`research-lead`** und tippe:

> Vergleiche drei Vektordatenbanken — **Qdrant**, **Weaviate** und **Milvus** — für den
> Einsatz in unserem Intelligent-Knowledge-Base-Dienst (Java/Spring-Backend, Self-Hosting,
> einige Millionen Embeddings). Ich brauche pro DB: Lizenz/Kosten, Self-Hosting-Aufwand,
> Java-Client-Reife, Performance/Skalierung und die wichtigsten Vor-/Nachteile.
> Schreib mir am Ende eine klare Empfehlung als Report.

**Warum dieser Case gut ist:** drei **unabhängige** Sub-Topics (eine DB pro Topic) →
der Agent MUSS sie parallel fan-outen. Genau das macht den `run_agents`-Vorteil sichtbar.

### Was die Zuschauer sehen sollten (live mitkommentieren)
1. **Plan**: `research-lead` ruft `todo_write` → ein Plan mit ~4 Items erscheint.
2. **Paralleler Fan-out**: EIN `run_agents`-Call startet 3 `web-researcher`-Sub-Agenten
   gleichzeitig (im Trace-/Sub-Agent-UI als parallele Stränge sichtbar). Hier betonen:
   *"das lief früher nacheinander, jetzt gleichzeitig"*.
3. **Fact-Check**: ein zweiter `run_agents`-Call mit mehreren `verifier`-Tasks prüft die
   Kernaussagen (z.B. "Qdrant ist Apache-2.0", "Milvus skaliert auf Milliarden Vektoren")
   gegen die Quell-URLs.
4. **Report**: `workspace_write` schreibt `report.md`; der Agent meldet den Pfad und gibt
   eine kurze Chat-Zusammenfassung + Empfehlung.
5. **Beweis**: `report.md` im Workspace öffnen → fertiger, zitierter Vergleich.

---

## Backup-Prompts (falls ein Topic mau ist oder du variieren willst)

- *"Vergleiche **LangChain4j**, **Spring AI** und das **OpenAI-Java-SDK** als LLM-Integrations-Layer
  für ein Java-Backend: Reife, Feature-Umfang, Provider-Support, Community. Gib eine Empfehlung."*
- *"Recherchiere die aktuellen **Pflichten aus dem EU AI Act** für Anbieter von
  KI-Chat-Assistenten in drei Achsen: Transparenzpflichten, Risikoklassen, Fristen.
  Schreib einen kompakten Report mit Quellen."*
- *"Vergleiche **Keycloak**, **Authentik** und **Ory** als Self-Hosted-OIDC-Lösung:
  Setup-Aufwand, Feature-Set, Skalierung, Lizenz. Empfehlung am Ende."*

Alle drei haben dasselbe Muster: 3 unabhängige Achsen → paralleler Fan-out.

---

## Wenn etwas schiefgeht (Live-Recovery)

- **Kein paralleler Fan-out, sondern einzelne `run_agent`-Calls** → das Modell ist zu schwach
  oder zu vorsichtig. Recovery: nachschieben *"Recherchiere die drei Datenbanken parallel
  in einem einzigen run_agents-Aufruf."* Dauerhafte Lösung: stärkeres Modell (siehe Vorbereitung 2).
- **`web_search` liefert Fehler** → `TAVILY_API_KEY` fehlt im laufenden Prozess. Prüfen, dass
  die App aus der IntelliJ-Run-Config mit dem Env-Var gestartet wurde.
- **Sub-Agent gibt kaputtes JSON zurück** → einmal denselben Prompt erneut schicken; mit
  starkem Modell tritt das selten auf.
- **`report.md` fehlt** → der Agent hat nur im Chat geantwortet. Nachschieben:
  *"Schreib den Report als report.md ins Workspace."*

---

## Talking Points (Architektur-Story für die Demo)

- **Vorher**: Delegation war rein sequenziell — drei Recherchen = dreimal warten.
- **Jetzt**: `run_agents` fächert unabhängige Sub-Tasks parallel auf den Turn-Executor auf
  und sammelt alle Ergebnisse zusammen → Wall-Clock ≈ langsamster einzelner Task statt Summe.
- **Wie Claude**: kuratierte, benannte Sub-Agenten (kein ad-hoc-Agenten-Wildwuchs) +
  ein Orchestrator, der zerlegt, parallelisiert, **verifiziert** und synthetisiert.
- **Sicherheit/Qualität**: Sub-Agenten haben strikte Tool-Sets und liefern strukturiertes
  JSON; der `verifier` macht einen adversarialen Gegen-Check, bevor eine Aussage in den
  Report wandert — kein blindes Übernehmen von Halluzinationen.
