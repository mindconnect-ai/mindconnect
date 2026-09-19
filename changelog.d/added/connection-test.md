- **agents:** **a connection can be tried out from the Connections page.** A
  tool source that declares a `ConnectionSpec` may offer a `ConnectionTester`
  (`connectionTester()` on `ToolFactory` and `MultiToolProvider`); the page
  then shows a **Test** button on each of that provider's rows, runs the test
  right after a connection is added or edited — while the person who typed
  the password is still looking — and writes the verdict into the Status
  column, so a wrong password reads "error — the server refused the account"
  instead of every tool call failing the same way. An OAuth token is renewed
  before the test, as it would be before a tool call. `ToolRegistry`
  gained `connectionTesterOf(provider)`.
