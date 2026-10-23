package io.github.jaylu.muspecced;

import io.muserver.MuHandler;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.RouteHandler;
import io.muserver.openapi.OperationObject;
import io.muserver.openapi.ParameterObject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static io.github.jaylu.muspecced.SpeccedHandler.combine;
import static io.github.jaylu.muspecced.SpeccedHandler.context;
import static io.github.jaylu.muspecced.SpeccedHandler.route;
import static io.github.jaylu.muspecced.SpeccedHandler.specced;
import static io.muserver.Method.GET;
import static io.muserver.Method.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpeccedHandlers are values, so most of their behaviour can be checked without starting a server
 */
class SpeccedHandlerTest {

    private static final RouteHandler noop = (req, resp, params) -> {};

    @Test
    void aRouteIsAValueHoldingItsHandlerAndOperation() {
        SpeccedHandler h = route(GET, "/users/{id : [0-9]+}",
            op -> op.summary("Get a user").pathParam("id", long.class, "The ID").response(200, "OK"), noop);

        assertEquals(1, h.apiOperations().size());
        ApiOperation op = h.apiOperations().get(0);
        assertEquals(GET, op.method());
        assertEquals("/users/{id}", op.path());
        OperationObject operation = op.operation();
        assertEquals("Get a user", operation.summary());
        assertNull(operation.operationId(), "Operation IDs are generated when the document is assembled");
        ParameterObject id = operation.parameters().get(0);
        assertEquals("id", id.name());
        assertEquals("integer", id.schema().type());
        assertTrue(h.handler().toString().contains("/users/{id : [0-9]+}"), h.handler().toString());
    }

    @Test
    void creatingARouteRegistersNothing() {
        SpeccedHandler first = route(GET, "/a", op -> {}, noop);
        SpeccedHandler again = route(GET, "/a", op -> {}, noop);
        // Same operation twice is fine as a value; it only fails if both are put in one document
        assertEquals(first.apiOperations().get(0).path(), again.apiOperations().get(0).path());
    }

    @Test
    void contextsPrefixOperationsIncludingNestedOnes() {
        SpeccedHandler api = context("api",
            route(GET, "/users", op -> {}, noop),
            context("/v1/", route(GET, "/status", op -> {}, noop)));
        List<String> paths = new ArrayList<>();
        for (ApiOperation op : api.apiOperations()) {
            paths.add(op.path());
        }
        assertEquals(List.of("/api/users", "/api/v1/status"), paths);
    }

    @Test
    void theSameModuleCanBeMountedInTwoContexts() {
        List<SpeccedHandler> module = List.of(route(GET, "/status", op -> {}, noop));
        String json = OpenApiDocs.openApiDocs(io.muserver.openapi.InfoObjectBuilder.infoObject().withTitle("T").withVersion("1").build())
            .toJson(List.of(context("v1", module), context("v2", module)));
        assertTrue(json.contains("\"GET_v1_status\""), json);
        assertTrue(json.contains("\"GET_v2_status\""), json);
    }

    @Test
    void combineKeepsAllOperationsInOrder() {
        SpeccedHandler combined = combine(
            route(GET, "/a", op -> {}, noop),
            combine(route(GET, "/b", op -> {}, noop), route(POST, "/b", op -> {}, noop)));
        List<String> ops = new ArrayList<>();
        for (ApiOperation op : combined.apiOperations()) {
            ops.add(op.toString());
        }
        assertEquals(List.of("GET /a", "GET /b", "POST /b"), ops);
    }

    @Test
    void operationListsAreCopiedAndUnmodifiable() {
        List<ApiOperation> ops = new ArrayList<>(route(GET, "/a", op -> {}, noop).apiOperations());
        SpeccedHandler h = specced((req, resp) -> false, ops);
        ops.clear();
        assertEquals(1, h.apiOperations().size());
        assertThrows(UnsupportedOperationException.class, () -> h.apiOperations().clear());
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> specced(null));
        assertThrows(NullPointerException.class, () -> specced((req, resp) -> false, (ApiOperation) null));
        assertThrows(NullPointerException.class, () -> combine((SpeccedHandler) null));
        assertThrows(NullPointerException.class, () -> route(GET, "/a", op -> {}, null));
    }

    @Test
    void handlersAreTriedInOrderAndContextsRouteRequests() throws Exception {
        MuHandler first = (req, resp) -> {
            if (!req.relativePath().equals("/x")) return false;
            resp.write("first");
            return true;
        };
        MuHandler second = (req, resp) -> {
            resp.write("second");
            return true;
        };
        SpeccedHandler app = combine(
            context("ctx", specced(first)),
            specced(first),
            specced(second));
        MuServer server = MuServerBuilder.httpServer().addHandler(app.handler()).start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            assertEquals("first", client.send(HttpRequest.newBuilder(server.uri().resolve("/ctx/x")).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("first", client.send(HttpRequest.newBuilder(server.uri().resolve("/x")).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("second", client.send(HttpRequest.newBuilder(server.uri().resolve("/y")).build(), HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            server.stop();
        }
    }
}
