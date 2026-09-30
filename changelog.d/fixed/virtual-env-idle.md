- **agents:** **the virtual environment client no longer remembers every chat
  it ever served.** It kept each workspace's environment id and the list of
  copied uploads for as long as the server ran. It now forgets a workspace
  nobody used for `mindconnect.virtual-env.client.idle-timeout` (default 2h);
  the next call acquires the environment again and copies the uploads once
  more. Stopping idle environments stays with the virtual environment server,
  which already does it per template (`idle-stop`); keep the client timeout
  below the server's `retention`.
