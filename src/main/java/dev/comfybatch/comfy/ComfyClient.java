package dev.comfybatch.comfy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.comfybatch.config.AppProperties;
import dev.comfybatch.support.Json;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class ComfyClient {
    private final String base;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ComfyClient(AppProperties properties) {
        this.base = properties.comfyUrl().replaceAll("/$", "");
    }

    private record PromptRequest(
            JsonNode prompt,
            @JsonProperty("prompt_id") String promptId,
            @JsonProperty("client_id") String clientId) {}

    private record DeleteRequest(List<String> delete) {}

    private JsonNode request(String method, String path, Object body) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (body == null) builder.GET();
        else
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2)
            throw new RemoteError("HTTP " + response.statusCode() + " " + response.body());
        return Json.read(response.body());
    }

    public String submit(JsonNode workflow, String promptId, String clientId) throws Exception {
        return request("POST", "/prompt", new PromptRequest(workflow, promptId, clientId))
                .path("prompt_id")
                .asText();
    }

    public PromptHistory history(String promptId) throws Exception {
        return new PromptHistory(request("GET", "/history/" + promptId, null).path(promptId));
    }

    public QueueSnapshot queue() throws Exception {
        return QueueSnapshot.from(request("GET", "/queue", null));
    }

    public void cancel(String promptId) throws Exception {
        QueueSnapshot queue = queue();
        if (queue.running().contains(promptId))
            request("POST", "/interrupt", Json.MAPPER.createObjectNode());
        else if (queue.pending().contains(promptId))
            request("POST", "/queue", new DeleteRequest(List.of(promptId)));
    }

    public byte[] image(OutputImage image) throws Exception {
        String query =
                "filename="
                        + encode(image.filename())
                        + "&subfolder="
                        + encode(image.subfolder())
                        + "&type="
                        + encode(image.type());
        var request =
                HttpRequest.newBuilder(URI.create(base + "/view?" + query))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200)
            throw new RemoteError("view HTTP " + response.statusCode());
        return response.body();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public WebSocket connect(String clientId, Consumer<ComfyMessage> callback) throws Exception {
        var uri = URI.create(base.replaceFirst("^http", "ws") + "/ws?clientId=" + clientId);
        return http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, new ComfyWebSocketListener(callback))
                .get(6, TimeUnit.SECONDS);
    }

    public static class RemoteError extends Exception {
        public RemoteError(String message) {
            super(message);
        }
    }
}
