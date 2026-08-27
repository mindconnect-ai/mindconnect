package ai.mindconnect.taskqueue.clusterdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One jar, two roles ({@code mindconnect.cluster.role}): {@code master} serves
 * the dashboard and the tasks view, submits, and runs the worker JVMs;
 * {@code worker} runs the unchanged LocalTaskQueue with the registered task
 * workers. Both share one Postgres — the store IS the cluster; the REST
 * nudges between the processes only make it fast.
 *
 * <p>Self-contained on purpose: UI, workers and task types live in this
 * module (copied from the single-process demo, then evolved independently).
 */
@SpringBootApplication
public class ClusterDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClusterDemoApplication.class, args);
    }
}
