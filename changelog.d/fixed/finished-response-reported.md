- **agents:** **a finished response is reported as finished.** A turn's events
  reach their listener on a thread of their own, so the turn could end before its
  final event had been handed over. A caller reading the result at that moment —
  a synchronous `POST` to the responses API, an approval answer — now and then got
  the response back still `in_progress`. The turn's outcome now waits until its
  final event has reached the listener.
