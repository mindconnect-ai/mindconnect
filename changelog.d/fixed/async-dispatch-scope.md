- **admin-ui:** **a chat stream no longer ends in "The connection broke off".**
  When an SSE stream finished, Spring dispatched the request back into the
  servlet on another thread; the namespace filter skipped that dispatch, the
  namespace check found no scope and threw, and Tomcat aborted a response that
  was nearly done — the answer arrived, followed by a broken connection, and
  an error from the model never reached the user at all. The async dispatch now
  runs in the namespace and on behalf of the user the request was answered for.
