- **agents:** **continuing a turn at its second approval no longer replays what
  followed the first.** When the first question of a turn was answered without
  taking a handle (`POST …/approvals/{callId}`) and the turn stopped at a
  second one, `…/approvals/{callId}/continue` started at the first gate and
  sent its tokens and tool events a second time. It now continues from the
  latest gate.
- **agents:** **a question answered the moment it is asked no longer ends a
  protocol turn empty-handed.** When an approval was answered elsewhere just as
  the caller's handle looked at it, the caller got `INCOMPLETE` with no open
  question and never heard the rest of the turn. The handle now keeps listening
  and completes with the turn's answer.
- **agents:** **a virtual environment's own folders (`.home`, `.mc`) stay out of
  the file explorer however they are addressed.** They were hidden in the
  workspace's top listing, but asking for them by path listed, downloaded and
  zipped them. Opening, listing and zipping now refuse them like preview, save
  and delete already did.
