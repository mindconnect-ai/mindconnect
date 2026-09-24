- **agents:** **LLM configs can carry token prices, per model and validity
  period.** The admin UI's LLM config screen has a **Pricing** section under
  each concrete config: periods with a *model*, a *valid from* (inclusive) and
  an optional *valid to* (exclusive), a currency (`USD` by default) and rates
  per million input, output and cached-input tokens. A price applies only to
  calls the config served with that model — a config's model changes over time
  (an edit, a `${OPENAI_MODEL:…}` placeholder), and a call is never priced with
  another model's rate — so one config carries prices for several models, shown
  grouped by model. The dialog prefills the model the config serves now,
  placeholders resolved as the server resolves them; the model matches
  ignoring case. Periods of one config and model may not overlap, and an alias
  has none of its own — it shows *Priced by &lt;target&gt;*. Prices are a new
  entity next to the configs (`LlmPriceRepository`, file, in-memory and
  Postgres adapters, table `mc_llm_price` with `config_name` and `model`
  columns); `LlmConfig` is unchanged. A renamed config takes its prices along,
  a deleted one removes them. `priceAt(configName, model, instant)` and
  `LlmPrice.cost(...)` are there for usage reports to price calls with the rate
  of their model and day.
