- **agents:** **LLM configs can carry token prices, per validity period.** The
  admin UI's LLM config screen has a **Pricing** section under each concrete
  config: periods with a *valid from* (inclusive) and an optional *valid to*
  (exclusive), a currency (`USD` by default) and rates per million input,
  output and cached-input tokens. Periods of one config may not overlap, and
  an alias has none of its own — it shows *Priced by &lt;target&gt;*. Prices
  are a new entity next to the configs (`LlmPriceRepository`, file, in-memory
  and Postgres adapters, table `mc_llm_price`); `LlmConfig` is unchanged. A
  renamed config takes its prices along, a deleted one removes them.
  `priceAt(configName, instant)` and `LlmPrice.cost(...)` are there for usage
  reports to price calls with the rate of their day.
