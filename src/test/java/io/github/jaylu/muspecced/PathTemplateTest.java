package io.github.jaylu.muspecced;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathTemplateTest {

    @Test
    void regexesAreRemovedFromThePath() {
        PathTemplate t = PathTemplate.parse("", "/users/{id : [0-9]+}/orders/{orderId}");
        assertEquals("/users/{id}/orders/{orderId}", t.openApiPath);
        assertEquals("/users/{}/orders/{}", t.shape);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("id", "[0-9]+");
        expected.put("orderId", null);
        assertEquals(expected, t.params);
    }

    @Test
    void slashesAreNormalised() {
        assertEquals("/", PathTemplate.parse("", "").openApiPath);
        assertEquals("/", PathTemplate.parse("/", "/").openApiPath);
        assertEquals("/users", PathTemplate.parse("", "users/").openApiPath);
        assertEquals("/v1/users", PathTemplate.parse("v1/", "/users").openApiPath);
        assertEquals("/v1", PathTemplate.parse("/v1", "/").openApiPath);
    }

    @Test
    void paramsCanBePartOfASegment() {
        PathTemplate t = PathTemplate.parse("", "/files/{name}.{ext : [a-z]+}");
        assertEquals("/files/{name}.{ext}", t.openApiPath);
        assertEquals(Arrays.asList("name", "ext"), Arrays.asList(t.params.keySet().toArray()));
    }
}
