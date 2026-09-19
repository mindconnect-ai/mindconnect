- **agents:** **an OAuth client id set after the first start is used.** A module's
  shipped OAuth registration is stored on the first start, and an operator who set
  their own client id (`MC_MICROSOFT_CLIENT_ID`, or the variable of another module)
  only afterwards was ignored for good — every "Connect" still went to Microsoft
  with the placeholder id. The operator's client id and secret now go onto the
  stored registration on every start; their other edits to it stay.
