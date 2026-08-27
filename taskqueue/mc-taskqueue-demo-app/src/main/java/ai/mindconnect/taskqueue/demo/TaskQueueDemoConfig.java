package ai.mindconnect.taskqueue.demo;

import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.taskqueue.LoggingTaskListener;
import ai.mindconnect.taskqueue.MdcTaskAdvisor;
import ai.mindconnect.taskqueue.bridge.TaskChannelBridge;
import ai.mindconnect.taskqueue.demo.worker.CountdownWorker;
import ai.mindconnect.taskqueue.demo.worker.ScrapePageWorker;
import ai.mindconnect.taskqueue.SharedStateStore;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemorySharedStateStore;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskQueueDemoConfig {

    @Bean
    public InMemoryTaskStore taskStore() {
        return new InMemoryTaskStore();
    }

    /** What tasks working on the same crawl agree on — the visited set. */
    @Bean
    public SharedStateStore sharedStateStore() {
        return new InMemorySharedStateStore();
    }

    @Bean
    public ChannelRegistry channelRegistry() {
        return new ChannelRegistry();
    }

    @Bean(destroyMethod = "close")
    public LocalTaskQueue taskQueue(InMemoryTaskStore store, ChannelRegistry channels,
            @Value("${mindconnect.taskqueue.retention:PT1H}") java.time.Duration retention) {
        // In-memory store — without retention every finished crawl stays on
        // the heap forever. PT0S in the yaml turns the sweep off.
        return new LocalTaskQueue(store)
                .withRetention(retention.isZero() ? null : retention)
                .addListener(new LoggingTaskListener())
                .addListener(TaskChannelBridge.global(channels))
                .addAdvisor(MdcTaskAdvisor.withPayloadKeys("url", "startUrl"));
    }

    @Bean
    public ApplicationRunner registerWorkers(LocalTaskQueue queue,
                                             ScrapePageWorker scrape,
                                             CountdownWorker countdown) {
        return args -> {
            queue.register(ScrapePageWorker.TYPE, scrape);
            queue.register(CountdownWorker.TYPE, countdown);
        };
    }
}
