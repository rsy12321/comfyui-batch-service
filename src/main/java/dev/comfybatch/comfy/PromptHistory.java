package dev.comfybatch.comfy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public record PromptHistory(JsonNode raw) {
    public boolean exists() {
        return !raw.isMissingNode();
    }

    public boolean succeeded() {
        return raw.path("status").path("status_str").asText().equals("success");
    }

    public String statusDescription() {
        return raw.path("status").toString();
    }

    public List<OutputImage> images() {
        var images = new ArrayList<OutputImage>();
        for (var output : raw.path("outputs"))
            for (var image : output.path("images")) images.add(OutputImage.from(image));
        return images;
    }
}
