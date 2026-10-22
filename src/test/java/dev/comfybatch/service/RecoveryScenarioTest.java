package dev.comfybatch.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.comfybatch.comfy.*;
import dev.comfybatch.domain.TaskState;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;

class RecoveryScenarioTest extends ScenarioSupport {
    private static final String EMPTY_QUEUE = "{\"queue_running\":[],\"queue_pending\":[]}";
    private static final String RUNNING_QUEUE =
            "{\"queue_running\":[[1,\"slow\"]],\"queue_pending\":[]}";

    private void enqueue(MockWebServer server, String... responses) {
        for (String response : responses) server.enqueue(new MockResponse().setBody(response));
    }

    private void runWorker(MockWebServer server, String taskId, long timeout) throws Exception {
        server.start();
        TaskWorker execution = workerFor(server.url("/").toString(), timeout);
        try {
            execution.run(taskId);
        } finally {
            execution.stop();
        }
    }

    private byte[] png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void restartRestoresHistoryWithoutResubmittingWhenWebsocketIsDown() throws Exception {
        // given
        String taskId = persistedTask("saved", System.currentTimeMillis());
        lifecycle.transition(taskId, TaskState.RUNNING, "before restart");
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            enqueue(
                    server,
                    """
    {"saved":{"status":{"status_str":"success"},"outputs":{"9":{"images":[
    {"filename":"a.png","subfolder":"","type":"output"}]}}}}
    """);
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(png())));
            // when
            runWorker(server, taskId, 60);
            // then
            assertEquals(TaskState.SUCCEEDED, tasks.require(taskId).state());
            assertEquals(1, tasks.require(taskId).attempt());
            assertEquals(1, results.findByTask(taskId).size());
            assertEquals(2, results.findByTask(taskId).getFirst().width());
            for (int i = 0; i < 3; i++)
                assertNotEquals("/prompt", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        }
    }

    @Test
    void promptMissingFromBothHistoryAndQueueSchedulesRetry() throws Exception {
        // given
        String taskId = persistedTask("missing", System.currentTimeMillis());
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            enqueue(server, "{}", EMPTY_QUEUE, EMPTY_QUEUE);
            // when
            runWorker(server, taskId, 60);
            // then
            assertNull(tasks.require(taskId).promptId());
            assertTrue(tasks.require(taskId).error().contains("absent"));
        }
    }

    @Test
    void queuedCancellationDoesNotInterruptAnUnrelatedRunningPrompt() throws Exception {
        // given
        try (var server = new MockWebServer()) {
            enqueue(
                    server,
                    "{\"queue_running\":[[1,\"other\"]],\"queue_pending\":[[2,\"mine\"]]}",
                    "{}");
            server.start();
            // when
            new ComfyClient(properties(server.url("/").toString(), 60)).cancel("mine");
            // then
            assertEquals("/queue", server.takeRequest().getPath());
            var request = server.takeRequest();
            assertEquals("/queue", request.getPath());
            assertEquals("{\"delete\":[\"mine\"]}", request.getBody().readUtf8());
        }
    }

    @Test
    void websocketHandshakeDeliversTypedProgressMessage() throws Exception {
        // given
        try (var server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse()
                            .withWebSocketUpgrade(
                                    new WebSocketListener() {
                                        @Override
                                        public void onOpen(WebSocket socket, Response response) {
                                            socket.send(
                                                    "{\"type\":\"progress\",\"data\":{\"prompt_id\":\"own\","
                                                            + "\"value\":1,\"max\":2}}");
                                        }
                                    }));
            server.start();
            var received = new LinkedBlockingQueue<ComfyMessage>();
            // when
            var socket =
                    new ComfyClient(properties(server.url("/").toString(), 60))
                            .connect("client", received::add);
            var message = received.poll(3, TimeUnit.SECONDS);
            socket.abort();
            // then
            assertNotNull(message);
            assertEquals("progress", message.type());
            assertEquals("own", message.promptId());
            assertEquals(1, message.value());
            assertEquals(2, message.max());
        }
    }

    @Test
    void timedOutAttemptIsInterruptedBeforeSchedulingRetry() throws Exception {
        // given
        String taskId = persistedTask("slow", System.currentTimeMillis() - 5000);
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            enqueue(server, "{}", RUNNING_QUEUE, "{}", RUNNING_QUEUE, "{}", EMPTY_QUEUE);
            // when
            runWorker(server, taskId, 1);
            // then
            assertNull(tasks.require(taskId).promptId());
            assertTrue(tasks.require(taskId).error().contains("timeout"));
            boolean interrupted = false;
            for (int i = 0; i < 7; i++)
                if ("/interrupt".equals(server.takeRequest(1, TimeUnit.SECONDS).getPath()))
                    interrupted = true;
            assertTrue(interrupted);
        }
    }

    @Test
    void unreachableComfyPreservesPromptIdentityForLaterReconciliation() throws Exception {
        // given
        String taskId = persistedTask("existing", System.currentTimeMillis());
        try (var server = new MockWebServer()) {
            for (int i = 0; i < 3; i++)
                server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            // when
            runWorker(server, taskId, 60);
            // then
            assertEquals("existing", tasks.require(taskId).promptId());
            assertEquals(1, tasks.require(taskId).attempt());
            assertEquals(TaskState.SUBMITTED, tasks.require(taskId).state());
        }
    }
}
