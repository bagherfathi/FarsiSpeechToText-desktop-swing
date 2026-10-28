package com.example;

import com.google.gson.*;

final class RecognitionText {
    private RecognitionText() {}
    static String fromJson(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return "";
            JsonElement text = root.getAsJsonObject().get("text");
            return text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()
                    ? text.getAsString().trim() : "";
        } catch (JsonParseException | IllegalStateException ex) {
            return "";
        }
    }
}
