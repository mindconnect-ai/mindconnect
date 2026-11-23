package ai.mindconnect.agent.runtime.capabilities.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes traces to the log: what there is when nobody set up a place to keep them. */
public final class LoggingTraceSink implements TraceSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingTraceSink.class);

    @Override
    public void record(TraceRecord trace) {
        log.info("{} {} agent={} session={} turn={} took={}ms{}", trace.kind(), trace.name(), trace.subject().agent(),
                trace.subject().sessionId().value(), trace.subject().turnId().value(), trace.duration().toMillis(),
                trace.error() == null ? "" : " error=" + trace.error());
    }
}
