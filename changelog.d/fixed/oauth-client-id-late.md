- **agents:** **an OAuth client id set after the first start is used.** A module's
  shipped OAuth registration is stored on the first start, and an operator who set
  their own client id (`MC_MICROSOFT_CLIENT_ID`, or the variable of another module)
  only afterwards was ignored for good — every "Connect" still went to Microsoft
  with the placeholder id. The operator's client id and secret now go onto the
  stored registration on every start; their other edits to it stay.
- **agents:** **"Connect with Microsoft" reaches Microsoft.** The button was an
  API call the page fetched, and a consent page cannot be fetched into the page:
  the browser reported the backend as unreachable. It is a real link now and opens
  the sign-in in a new tab; the account is on the card there when the provider
  sends the browser back.
