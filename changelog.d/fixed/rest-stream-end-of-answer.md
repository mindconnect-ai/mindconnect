- **agents:** **the REST chat stream no longer loses the end of an answer.**
  `POST /api/sessions/{id}/chat` closed its Server-Sent Events stream the moment the
  turn finished, while the last text deltas and the `done` frame were still on their
  way — a fast final round could reach the client without its ending. The stream now
  closes once its `done` frame has been sent.
