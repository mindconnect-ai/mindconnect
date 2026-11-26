- **llm configs:** **a config renamed or deleted through the REST API no longer leaves its prices behind.**
  Only the admin screen moved a config's prices to its new name and removed
  them with the config; `POST /api/llm-configs` and `DELETE /api/llm-configs/{id}`
  saved and deleted the config alone, so its prices stayed under a name nothing
  used any more. Both now do what the screen does. An LM Studio config saved
  through the API without a key gets the placeholder key its local server
  expects, as one saved on the screen always did.
