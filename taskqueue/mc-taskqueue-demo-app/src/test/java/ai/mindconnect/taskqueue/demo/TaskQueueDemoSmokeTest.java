package ai.mindconnect.taskqueue.demo;

import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskQueueDemoSmokeTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    LocalTaskQueue queue;

    @Test
    void boardAndNewTaskPagesRenderAsJson() {
        for (String path : List.of("/tasks", "/tasks/new")) {
            ResponseEntity<String> response = rest.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(jsonAccept()), String.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).as(path).isTrue();
            assertThat(response.getBody()).contains("\"node\"");
        }
    }

    @Test
    void submittedCountdownRunsToCompletion() {
        ResponseEntity<String> response = rest.exchange("/tasks", HttpMethod.POST,
                new HttpEntity<>(Map.of("taskType", "countdown", "p_steps", 2, "p_delayMs", 10),
                        jsonAccept()),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("Submitted");

        String id = queue.submit(TaskSubmission.of("countdown",
                Map.of("steps", 2, "delayMs", 10, "failOnStep", -1)));
        TaskRecord done = queue.await(id, Duration.ofSeconds(10));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("counted 2 steps");
    }

    private static HttpHeaders jsonAccept() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
