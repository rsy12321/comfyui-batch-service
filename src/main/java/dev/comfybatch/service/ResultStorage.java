package dev.comfybatch.service;

import dev.comfybatch.comfy.*;
import dev.comfybatch.config.AppProperties;
import dev.comfybatch.domain.*;
import dev.comfybatch.support.Json;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class ResultStorage {
    private final Path root;
    private final ComfyClient comfy;

    public ResultStorage(AppProperties properties, ComfyClient comfy) {
        this.root = Path.of(properties.storage()).toAbsolutePath().normalize();
        this.comfy = comfy;
    }

    public StoredImage download(String taskId, String promptId, int index, OutputImage output)
            throws Exception {
        byte[] data = comfy.image(output);
        if (data.length < 8
                || data[0] != (byte) 137
                || data[1] != 80
                || data[2] != 78
                || data[3] != 71) throw new ComfyClient.RemoteError("Output is not PNG");
        var image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) throw new ComfyClient.RemoteError("Unreadable PNG");
        String relative = taskId + "/" + promptId + "-" + index + ".png";
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".part");
        Files.write(temporary, data);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        return new StoredImage(relative, Json.hash(data), image.getWidth(), image.getHeight());
    }

    public Path requireFile(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new NotFoundException();
        return path;
    }
}
