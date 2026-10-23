package io.github.jaylu.muspecced;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** JSON helpers for tests */
final class Json {
    static final ObjectMapper mapper = new ObjectMapper();

    static JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The sorted field names of a JSON object. mu-server 2.3.2 copies maps into a HashMap when building
     * the OpenAPI model, so the declared order of paths, properties and media types is not kept.
     */
    static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.sort(null);
        return keys;
    }

    static List<String> sorted(String... values) {
        List<String> list = new ArrayList<>(List.of(values));
        list.sort(null);
        return list;
    }

    static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    static JsonNode param(JsonNode op, String in, String name) {
        for (JsonNode p : op.get("parameters")) {
            if (p.get("in").asText().equals(in) && p.get("name").asText().equals(name)) {
                return p;
            }
        }
        throw new AssertionError("No " + in + " parameter " + name + " in " + op.get("parameters"));
    }

    private Json() {}
}
