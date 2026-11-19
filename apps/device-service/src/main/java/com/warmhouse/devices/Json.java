package com.warmhouse.devices;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private Json() {}

    public static Map<String, Object> object(byte[] bytes) {
        try {
            var object = MAPPER.readValue(bytes, MAP);
            if (object == null) {
                throw new ApiException(400, "JSON object is required");
            }

            return object;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ApiException(400, "Malformed JSON object");
        }
    }

    public static Map<String, Object> object(String value) {
        return object(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
