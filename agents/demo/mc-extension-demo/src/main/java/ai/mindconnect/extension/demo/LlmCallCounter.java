package ai.mindconnect.extension.demo;

import java.util.concurrent.atomic.AtomicLong;

/** How many LLM calls the decorator has seen since the runtime started — a bean the demo feature registers. */
public final class LlmCallCounter {

    private final AtomicLong calls = new AtomicLong();

    void increment() {
        calls.incrementAndGet();
    }

    public long calls() {
        return calls.get();
    }
}
