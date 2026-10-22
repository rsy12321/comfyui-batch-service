package dev.comfybatch.comfy;

import dev.comfybatch.support.Json;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ComfyWebSocketListener implements WebSocket.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(ComfyWebSocketListener.class);
    private final StringBuilder buffer = new StringBuilder();
    private final Consumer<ComfyMessage> callback;

    ComfyWebSocketListener(Consumer<ComfyMessage> callback) {
        this.callback = callback;
    }

    @Override
    public void onOpen(WebSocket socket) {
        socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            try {
                callback.accept(ComfyMessage.from(Json.read(buffer.toString())));
            } catch (RuntimeException exception) {
                LOG.warn(
                        "ComfyUI message handling failed; history polling remains active",
                        exception);
            }
            buffer.setLength(0);
        }
        socket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
        socket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        LOG.warn("ComfyUI WebSocket disconnected; history polling remains active", error);
    }
}
