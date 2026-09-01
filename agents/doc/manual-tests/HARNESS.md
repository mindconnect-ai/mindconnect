# Driving the suite from a browser

The cases in this directory are written for a human with a browser. An LLM
executing them needs the same browser, plus a way to put evidence on disk —
a screenshot the reader can check beats a sentence claiming the card appeared.

This file is the setup that works. It is not a precondition for the cases:
anyone clicking through them by hand can ignore it.

## What you need

- The admin UI on <http://localhost:9090> (see the run command in the repo
  README; `agents/server/mc-agent-admin-ui-app/start.sh` loads `mc.env` first).
- Google Chrome, and Playwright's node package. Nothing has to be added to the
  repo — install it in a scratch directory:

      mkdir -p /tmp/mt && cd /tmp/mt
      npm i playwright@1.49.0 --no-audit --no-fund

  `channel: 'chrome'` drives the installed Chrome, so no browser download is
  needed.

## The one trap

**Never wait for `networkidle`.** A chat page holds its session's SSE stream
open for as long as it is mounted, so the network never goes idle and every
`goto` waits out its timeout. Wait for something real instead:

```js
await page.goto(url, { waitUntil: 'domcontentloaded' });
await page.waitForSelector('textarea');
```

The same is true of any wait that assumes requests stop arriving. Wait for the
element, the text, or the absence of the one you expect to vanish.

## Skeleton

```js
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ channel: 'chrome' });
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

  await page.goto('http://localhost:9090/chat', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('textarea');

  await page.fill('textarea', 'Suche im Web nach dem Wetter in Hamburg.');
  await page.getByRole('button', { name: 'Send' }).click();

  // An approval card, a task card, a token: wait for the thing, not for quiet.
  await page.waitForSelector('text=Approval required', { timeout: 60000 });
  await page.screenshot({ path: 'artifacts/approval-card.png' });

  await browser.close();
})();
```

Authentication needs no handling: the dev setup signs every request in as
`mc_user`.

## Evidence

Screenshots belong in the run's `artifacts/` directory, named after the case
and the step they prove — `approval-deny-01-card.png`, not `screenshot3.png`.
Take one at each step whose Expected is visual; for assertions about stored
data, a JSON or curl excerpt in the same directory is the better artifact.

`page.screenshot({ fullPage: true })` captures a long conversation that does
not fit the viewport.

## Reading the stored truth

Several cases assert on what was persisted rather than on what is drawn. That
lives under the server's working directory:

    data/users/<user>/sessions/<sessionId>/session.json
    data/users/<user>/sessions/<sessionId>/working-memory.json
    data/system/agents/<agentId>.json

Reading those files directly is usually faster and always more precise than
hunting through the UI — and it is what the cases mean by "the conversation
JSON under the data directory".
