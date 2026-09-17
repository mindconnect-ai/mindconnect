# ERNI AI — a branding example

What `mindconnect.branding` looks like when an installation is dressed as
somebody else's. Run it with the `erni` Spring profile:

```bash
MINDCONNECT_ENCRYPTION_SECRET_KEY="change-me-to-a-32-char-secret!!!" \
mvn -f agents/server/mc-agent-admin-ui-app/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=erni
```

The profile (`src/main/resources/application-erni.yaml`) points
`assets-dir` at this directory, so the three files here are served at
`/branding/**` and nothing is built into the app:

| File | What it is |
|---|---|
| `logo.svg` | the mark beside the heading, and on the login card |
| `favicon.svg` | the browser tab's icon |
| `erni.css` | the palette, as framework tokens |

## The logos are placeholders

`logo.svg` and `favicon.svg` are stand-ins drawn for this example — **not**
the official ERNI assets, and not usable as them. Drop the real files in
under the same names and the instance picks them up on the next page load;
no rebuild, no restart.

Third-party logos and names are trademarks of their owners; an installation
using them needs that owner's permission. Which is the point of keeping them
out here: branding is a deployment concern, so the assets live next to the
deployment rather than in this repository.
