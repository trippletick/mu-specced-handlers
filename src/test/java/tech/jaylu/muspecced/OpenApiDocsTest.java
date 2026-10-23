package tech.jaylu.muspecced;

import com.fasterxml.jackson.databind.JsonNode;
import io.muserver.ContextHandlerBuilder;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.RouteHandler;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static tech.jaylu.muspecced.Json.keys;
import static tech.jaylu.muspecced.Json.sorted;
import static tech.jaylu.muspecced.SpeccedHandler.context;
import static tech.jaylu.muspecced.SpeccedHandler.route;
import static io.muserver.Method.GET;
import static io.muserver.Method.POST;
import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static io.muserver.openapi.OpenAPIObjectBuilder.openAPIObject;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static io.muserver.openapi.TagObjectBuilder.tagObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how documents are assembled from lists of handlers
 */
class OpenApiDocsTest {

    private static final RouteHandler noop = (req, resp, params) -> {};
    private final OpenApiDocs docs = OpenApiDocs.openApiDocs(infoObject().withTitle("Test").withVersion("1").build());

    @Test
    void theDocumentOnlyContainsTheHandlersPassedIn() {
        SpeccedHandler a = route(GET, "/a", op -> {}, noop);
        SpeccedHandler b = route(GET, "/b", op -> {}, noop);
        assertEquals(List.of("/a"), keys(Json.parse(docs.toJson(List.of(a))).get("paths")));
        assertEquals(sorted("/a", "/b"), keys(Json.parse(docs.toJson(List.of(a, b))).get("paths")));
    }

    @Test
    void theSameOperationInTwoHandlersFailsEvenWithDifferentParamNames() {
        List<SpeccedHandler> handlers = List.of(
            route(GET, "/users/{id}", op -> {}, noop),
            route(GET, "/users/{userId : [0-9]+}", op -> {}, noop));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> docs.serve(handlers));
        assertTrue(e.getMessage().contains("documented more than once"), e.getMessage());
        // A different method on the same path is fine
        docs.serve(route(GET, "/users/{id}", op -> {}, noop), route(POST, "/users/{id}", op -> {}, noop));
    }

    @Test
    void operationIdsMustBeUnique() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> docs.serve(
            route(GET, "/a", op -> op.operationId("same"), noop),
            route(GET, "/b", op -> op.operationId("same"), noop)));
        assertTrue(e.getMessage().contains("'same'"), e.getMessage());
    }

    @Test
    void generatedOperationIdsUseTheFinalPath() {
        JsonNode doc = Json.parse(docs.toJson(List.of(context("api", route(GET, "/users/{id}", op -> {}, noop)))));
        assertEquals("GET_api_users__id_", doc.at("/paths/~1api~1users~1{id}/get/operationId").asText());
    }

    @Test
    void theContextPathIsUsedAsTheServerUnlessServersAreGiven() {
        List<SpeccedHandler> handlers = List.of(route(GET, "/a", op -> {}, noop));
        assertEquals("/api", docs.document(handlers, "/api/").servers().get(0).url());
        assertEquals(null, docs.document(handlers, "").servers());

        OpenApiDocs withServers = OpenApiDocs.openApiDocs(openAPIObject()
            .withInfo(infoObject().withTitle("T").withVersion("1").build())
            .withServers(List.of(serverObject().withUrl("https://example.org").build())));
        assertEquals("https://example.org", withServers.document(handlers, "/api").servers().get(0).url());
    }

    @Test
    void tagsFromTheBaseThenSettingsThenOperationsAreListedOnce() {
        OpenApiDocs withTags = OpenApiDocs.openApiDocs(openAPIObject()
                .withInfo(infoObject().withTitle("T").withVersion("1").build())
                .withTags(List.of(tagObject().withName("base").build())))
            .withTag("described", "A described tag")
            .withTag("base", "Now described");
        JsonNode tags = Json.parse(withTags.toJson(List.of(
            route(GET, "/a", op -> op.tag("used", "described"), noop)))).get("tags");
        List<String> names = new ArrayList<>();
        tags.forEach(t -> names.add(t.get("name").asText()));
        assertEquals(List.of("base", "described", "used"), names);
        assertEquals("Now described", tags.get(0).get("description").asText());
    }

    @Test
    void settingsAreImmutable() {
        OpenApiDocs tagged = docs.withTag("x", "X");
        assertTrue(docs.toJson(List.of()).indexOf("\"tags\"") < 0);
        assertTrue(tagged.toJson(List.of()).contains("\"x\""));
    }

    @Test
    void servedUnderAContextTheDocumentUsesTheContextAsServerAndACustomJsonPath() throws Exception {
        MuServer server = MuServerBuilder.httpServer()
            .addHandler(ContextHandlerBuilder.context("api").addHandler(docs.withJsonPath("docs/openapi.json")
                .serve(route(GET, "/hello", op -> {}, (req, resp, params) -> resp.write("hi")))))
            .start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/docs/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            JsonNode doc = Json.parse(resp.body());
            assertEquals("/api", doc.at("/servers/0/url").asText());
            assertEquals(List.of("/hello"), keys(doc.get("paths")));
            assertEquals("hi", client.send(HttpRequest.newBuilder(server.uri().resolve("/api/hello")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(404, client.send(HttpRequest.newBuilder(server.uri().resolve("/api/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop();
        }
    }
}
