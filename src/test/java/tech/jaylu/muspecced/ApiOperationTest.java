package tech.jaylu.muspecced;

import io.muserver.openapi.OperationObject;
import io.muserver.openapi.ParameterObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static tech.jaylu.muspecced.ApiOperation.apiOperation;
import static io.muserver.Method.CONNECT;
import static io.muserver.Method.GET;
import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiOperations built directly from mu-server's OperationObject, as a hand-written handler might
 */
class ApiOperationTest {

    @Test
    void pathsAreNormalisedAndRegexesRemoved() {
        ApiOperation op = apiOperation(GET, "users/{id : [0-9]+}/", operation(pathParam("id")));
        assertEquals("/users/{id}", op.path());
        assertEquals("GET /users/{id}", op.toString());
    }

    @Test
    void everyPathParamMustBeDeclaredInTheOperation() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            apiOperation(GET, "/users/{id}", operation()));
        assertTrue(e.getMessage().contains("[id]"), e.getMessage());
    }

    @Test
    void declaredPathParamsMustBeInThePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            apiOperation(GET, "/users", operation(pathParam("id"))));
        assertTrue(e.getMessage().contains("declares path parameters [id]"), e.getMessage());
    }

    @Test
    void methodMustBeDocumentable() {
        assertThrows(IllegalArgumentException.class, () -> apiOperation(null, "/a", operation()));
        assertThrows(IllegalArgumentException.class, () -> apiOperation(CONNECT, "/a", operation()));
        assertThrows(NullPointerException.class, () -> apiOperation(GET, "/a", null));
    }

    @Test
    void prefixesAreJoinedWithSingleSlashes() {
        ApiOperation op = apiOperation(GET, "/", operation());
        assertEquals("/v1", op.withPathPrefix("/v1/").path());
        assertEquals("/api/v1", op.withPathPrefix("v1").withPathPrefix("api").path());
    }

    private static OperationObject operation(ParameterObject... params) {
        return operationObject()
            .withParameters(List.of(params))
            .withResponses(responsesObject().withHttpStatusCodes(java.util.Map.of("200", responseObject().withDescription("OK").build())).build())
            .build();
    }

    private static ParameterObject pathParam(String name) {
        return parameterObject().withName(name).withIn("path").withRequired(true)
            .withSchema(schemaObject().withType("string").build()).build();
    }
}
