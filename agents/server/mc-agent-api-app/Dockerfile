# Runtime image for the agent REST API (SSE streaming).
#
# No browser stage: mc-agent-api-app does not depend on
# mc-agent-tools-web-browser, so Playwright and Chromium stay out and the
# image is a fraction of the admin UI's.
#
# The jar is built outside this file — in CI, or locally with
#   mvn -pl agents/server/mc-agent-api-app -am install -DskipTests
#
#   docker build -f agents/server/mc-agent-api-app/Dockerfile \
#                agents/server/mc-agent-api-app/target

ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${JRE_IMAGE} AS layers
WORKDIR /build
ARG JAR_FILE=*-exec.jar
COPY ${JAR_FILE} app.jar
# The destination must be empty, and /build already holds app.jar — so
# extract into a subdirectory rather than into the working directory.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM ${JRE_IMAGE} AS runtime
WORKDIR /app
RUN adduser --system --uid 10001 mindconnect

COPY --from=layers --chown=mindconnect /build/extracted/dependencies/ ./
COPY --from=layers --chown=mindconnect /build/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=mindconnect /build/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=mindconnect /build/extracted/application/ ./

RUN mkdir -p /app/data && chown mindconnect /app/data
VOLUME ["/app/data"]

USER mindconnect
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

# No /health endpoint and no actuator in the app, so this only proves Tomcat
# is accepting connections. See the note in the admin UI's Dockerfile.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD nc -z 127.0.0.1 8080 || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
