package ai.mindconnect.taskqueue.clusterdemo;

import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.PersistentChannels;
import ai.mindconnect.channel.jdbc.JdbcChannelStore;
import ai.mindconnect.taskqueue.LoggingTaskListener;
import ai.mindconnect.taskqueue.MdcTaskAdvisor;
import ai.mindconnect.taskqueue.SharedStateStore;
import ai.mindconnect.taskqueue.bridge.TaskEvent;
import ai.mindconnect.taskqueue.clusterdemo.worker.CountdownWorker;
import ai.mindconnect.taskqueue.clusterdemo.worker.ScrapePageWorker;
import ai.mindconnect.taskqueue.jdbc.JdbcSharedStateStore;
import ai.mindconnect.taskqueue.jdbc.JdbcTaskStore;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * The cluster wiring: everything the in-memory demo config provided, backed
 * by Postgres instead. Node identity is role + port — readable in the
 * {@code lease_owner} column, which is half the point of a demo.
 */
@Configuration
public class ClusterConfig {

    @Bean
    public ClusterProperties clusterProperties(
            @Value("${mindconnect.cluster.role:master}") String role,
            @Value("${server.port:9100}") int port,
            @Value("${mindconnect.cluster.master-url:http://localhost:9100}") String masterUrl,
            @Value("${mindconnect.cluster.workers:2}") int workers,
            @Value("${mindconnect.cluster.worker-base-port:9101}") int workerBasePort,
            @Value("${mindconnect.cluster.worker-concurrency:2}") int workerConcurrency,
            @Value("${mindconnect.cluster.lease:PT30S}") Duration lease,
            @Value("${mindconnect.cluster.retention.tasks:PT1H}") Duration taskRetention,
            @Value("${mindconnect.cluster.retention.channel-age:PT1H}") Duration channelMaxAge,
            @Value("${mindconnect.cluster.retention.channel-events:5000}") long channelKeepEvents) {
        return new ClusterProperties(role, port, masterUrl, workers, workerBasePort,
                workerConcurrency, lease, taskRetention, channelMaxAge, channelKeepEvents);
    }

    @Bean
    public DataSource dataSource(@Value("${mindconnect.cluster.db.url}") String url,
                                 @Value("${mindconnect.cluster.db.user}") String user,
                                 @Value("${mindconnect.cluster.db.password}") String password) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public JdbcTaskStore taskStore(DataSource dataSource, ClusterProperties cluster) {
        return new JdbcTaskStore(dataSource, cluster.nodeId(), cluster.lease()).initSchema();
    }

    @Bean
    public SharedStateStore sharedStateStore(DataSource dataSource) {
        return new JdbcSharedStateStore(dataSource).initSchema();
    }

    @Bean
    public JdbcChannelStore<TaskEvent> channelStore(DataSource dataSource) {
        return new JdbcChannelStore<>(dataSource, TaskEvent.class).initSchema();
    }

    @Bean
    public ChannelRegistry channelRegistry() {
        return new ChannelRegistry();
    }

    /**
     * The durable channels, retention included: every node keeps the shared
     * event log bounded to the newest {@code channel-keep-events} — several
     * sweepers on one store just agree.
     */
    @Bean(destroyMethod = "close")
    public PersistentChannels<TaskEvent> persistentChannels(
            JdbcChannelStore<TaskEvent> store, ChannelRegistry registry, ClusterProperties cluster) {
        return new PersistentChannels<>(store, registry)
                .withRetention(cluster.channelMaxAge(), cluster.channelKeepEvents(),
                        Duration.ofMinutes(1));
    }

    @Bean(destroyMethod = "close")
    public LocalTaskQueue taskQueue(JdbcTaskStore store, ClusterProperties cluster) {
        // Bounded on workers so the demo's sleep makes the distribution
        // VISIBLE (n at a time per node, the rest queue up) — safe here
        // because parents SUSPEND instead of blocking, so a bounded pool
        // cannot deadlock on parent/child chains. 0 = unbounded (master).
        int maxConcurrent = cluster.isWorker() ? cluster.workerConcurrency() : 0;
        LocalTaskQueue queue = new LocalTaskQueue(store, maxConcurrent)
                .withMaintenanceInterval(cluster.lease().dividedBy(3))
                .withRetention(cluster.taskRetention().isZero() ? null : cluster.taskRetention())
                .addListener(new LoggingTaskListener())
                .addAdvisor(MdcTaskAdvisor.withPayloadKeys("url"));
        return queue;
    }

    /** Only a worker registers task types — the master never claims. */
    @Bean
    public ApplicationRunner registerWorkers(LocalTaskQueue queue, ClusterProperties cluster,
                                             ScrapePageWorker scrape, CountdownWorker countdown) {
        return args -> {
            if (cluster.isWorker()) {
                queue.register(ScrapePageWorker.TYPE, scrape);
                queue.register(CountdownWorker.TYPE, countdown);
            }
        };
    }
}
