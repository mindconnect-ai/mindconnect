# Runtime image for the agent admin UI.
#
# The jar is built outside this file — in CI, or locally with
#   mvn -pl agents/server/mc-agent-admin-ui-app -am install -DskipTests
# — and passed in as JAR_FILE. Building Maven inside the image would drag a
# multi-gigabyte ~/.m2 into the build and buys nothing over the CI cache.
#
#   docker build -f agents/server/mc-agent-admin-ui-app/Dockerfile \
#                agents/server/mc-agent-admin-ui-app/target
#
# The context is the module's target directory, so only the jar is sent.

ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

# ── 1. split the fat jar ─────────────────────────────────────────────────────
# The dependency layer is 342 MB and changes only when dependencies do; the
# application layer is 2.6 MB. Deploying a new version therefore pushes and
# pulls single-digit megabytes instead of a third of a gigabyte.
FROM ${JRE_IMAGE} AS layers
WORKDIR /build
ARG JAR_FILE=*-exec.jar
COPY ${JAR_FILE} app.jar
# The destination must be empty, and /build already holds app.jar — so
# extract into a subdirectory rather than into the working directory.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ── 2. browser for the web-browser tools ─────────────────────────────────────
# mc-agent-tools-web-browser drives Playwright, which needs a real browser and
# its system libraries. Chromium only — firefox and webkit would add several
# hundred megabytes that nothing here launches.
#
# Do NOT narrow this with --only-shell, tempting as the 549 MB looks:
# Playwright.create() runs the driver's own "install" on startup, finds the
# browser set incomplete and tries to download the missing full Chromium at
# runtime. That download hangs here, so the first web_read_browser call never
# returns — no browser process, no CPU, no error.
#
# This layer changes only with the Playwright version, so redeployments never
# re-download it.
FROM ${JRE_IMAGE} AS browsers
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
# The CLI needs the Playwright jars on its classpath, but only while it runs.
# A COPY would leave them in a 358 MB layer that a later rm cannot reclaim, so
# bind-mount them for the duration of this one command instead.
RUN --mount=type=bind,from=layers,source=/build/extracted/dependencies/BOOT-INF/lib,target=/tmp/lib \
    java -cp "/tmp/lib/*" com.microsoft.playwright.CLI install --with-deps chromium \
 && rm -rf /var/lib/apt/lists/*

# ── 3. runtime ───────────────────────────────────────────────────────────────
FROM browsers AS runtime
WORKDIR /app
RUN useradd --system --create-home --uid 10001 mindconnect

# Ordered least- to most-volatile so Docker reuses the big layers.
COPY --from=layers --chown=mindconnect /build/extracted/dependencies/ ./
COPY --from=layers --chown=mindconnect /build/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=mindconnect /build/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=mindconnect /build/extracted/application/ ./

RUN mkdir -p /app/data /app/logs && chown mindconnect /app/data /app/logs
VOLUME ["/app/data", "/app/logs"]

USER mindconnect
EXPOSE 9090

# Heap follows the container limit instead of the host's RAM — without this a
# JVM on a 4 GB box claims a quarter of the whole machine per container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

# Everything the browser needs is baked in above. Forbid the runtime download
# outright so an incomplete image fails loudly on the first call instead of
# hanging on a fetch nobody is watching.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# The app exposes no /health endpoint and pulls in no actuator, so this only
# proves Tomcat is accepting connections. Adding spring-boot-starter-actuator
# and probing /actuator/health would be the stronger check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/9090' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
