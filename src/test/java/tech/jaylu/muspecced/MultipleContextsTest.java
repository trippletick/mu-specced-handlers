package tech.jaylu.muspecced;

import com.fasterxml.jackson.databind.JsonNode;
import io.muserver.ContextHandlerBuilder;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.RouteHandler;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static tech.jaylu.muspecced.Json.keys;
import static tech.jaylu.muspecced.Json.sorted;
import static tech.jaylu.muspecced.Json.texts;
import static tech.jaylu.muspecced.SpeccedHandler.context;
import static tech.jaylu.muspecced.SpeccedHandler.route;
import static io.muserver.Method.DELETE;
import static io.muserver.Method.GET;
import static io.muserver.Method.POST;
import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The same users and session handlers mounted under two contexts, documented in one OpenAPI document:
 * <pre>
 * /context-path-1/users, /context-path-1/users/{id}, /context-path-1/session
 * /context-path-2/users, /context-path-2/users/{id}, /context-path-2/session
 * </pre>
 */
class MultipleContextsTest {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final OpenApiDocs docs = OpenApiDocs.openApiDocs(infoObject().withTitle("Multi-context API").withVersion("1.0").build())
        .withTag("users", "Manage users")
        .withTag("session", "Log in and out");
    private static MuServer server;
    private static JsonNode doc;
    private static String json;

    /** Writes which context and route handled the request, so tests can check the routing */
    private static RouteHandler echo(String name) {
        return (req, resp, params) -> resp.write(req.contextPath() + " " + name + " " + params);
    }

    /** A reusable module. It knows nothing about contexts: the paths are relative to wherever it is mounted. */
    static final List<SpeccedHandler> usersAndSession = List.of(
        route(GET, "/users",
            op -> op.summary("List users").tag("users").response(200, "The users"),
            echo("list-users")),
        route(GET, "/users/{id : [0-9]+}",
            op -> op.summary("Get a user").tag("users")
                .pathParam("id", long.class, "The user ID")
                .response(200, "The user").response(404, "No such user"),
            echo("get-user")),
        route(POST, "/session",
            op -> op.summary("Log in").tag("session").response(201, "Logged in"),
            echo("login")),
        route(DELETE, "/session",
            op -> op.summary("Log out").tag("session").response(204, "Logged out"),
            echo("logout")));

    @BeforeAll
    static void start() throws Exception {
        server = MuServerBuilder.httpServer()
            .addHandler(docs.serve(
                context("context-path-1", usersAndSession),
                context("context-path-2", usersAndSession)))
            .start();
        HttpResponse<String> resp = get("/openapi.json");
        assertEquals(200, resp.statusCode());
        json = resp.body();
        doc = Json.parse(json);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void oneDocumentListsTheHandlersOfBothContexts() {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(json);
        assertNotNull(result.getOpenAPI());
        assertEquals(List.of(), result.getMessages());
        assertEquals(sorted(
            "/context-path-1/users", "/context-path-1/users/{id}", "/context-path-1/session",
            "/context-path-2/users", "/context-path-2/users/{id}", "/context-path-2/session"), keys(doc.get("paths")));
        assertEquals(sorted("post", "delete"), keys(doc.at("/paths/~1context-path-2~1session")));
        assertFalse(doc.has("servers"), "Served from the root, so the paths carry the context and no server is needed");
    }

    @Test
    void generatedOperationIdsIncludeTheContextSoTheyStayUnique() {
        List<String> ids = new ArrayList<>(doc.findValuesAsText("operationId"));
        ids.sort(null);
        assertEquals(sorted(
            "GET_context-path-1_users", "GET_context-path-1_users__id_", "POST_context-path-1_session",
            "DELETE_context-path-1_session",
            "GET_context-path-2_users", "GET_context-path-2_users__id_", "POST_context-path-2_session",
            "DELETE_context-path-2_session"), ids);
    }

    @Test
    void pathParametersAreDocumentedInEachContext() {
        for (String context : List.of("context-path-1", "context-path-2")) {
            JsonNode id = doc.at("/paths/~1" + context + "~1users~1{id}/get/parameters/0");
            assertEquals("id", id.get("name").asText());
            assertEquals("path", id.get("in").asText());
            assertEquals("integer", id.at("/schema/type").asText());
        }
    }

    @Test
    void tagsSharedByBothContextsAreListedOnce() {
        assertEquals(List.of("users", "session"), doc.get("tags").findValuesAsText("name"));
        assertEquals(List.of("users"), texts(doc.at("/paths/~1context-path-1~1users/get/tags")));
        assertEquals(List.of("users"), texts(doc.at("/paths/~1context-path-2~1users/get/tags")));
    }

    @Test
    void requestsReachTheHandlerInTheRightContext() throws Exception {
        assertEquals("/context-path-1 list-users {}", get("/context-path-1/users").body());
        assertEquals("/context-path-2 get-user {id=42}", get("/context-path-2/users/42").body());
        assertEquals("/context-path-2 login {}", client.send(HttpRequest.newBuilder(server.uri().resolve("/context-path-2/session"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(404, get("/users").statusCode());
        assertEquals(404, get("/context-path-1/users/abc").statusCode());
    }

    @Test
    void explicitOperationIdsMustBeUniqueAcrossContexts() {
        List<SpeccedHandler> fixedIds = List.of(route(GET, "/users", op -> op.operationId("listUsers"), echo("x")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> docs.serve(context("context-path-1", fixedIds), context("context-path-2", fixedIds)));
        assertEquals("The operation ID 'listUsers' of GET /context-path-2/users is already used by "
            + "GET /context-path-1/users", e.getMessage());

        // Fine: the module takes the ID prefix as an argument
        Function<String, SpeccedHandler> module =
            prefix -> route(GET, "/users", op -> op.operationId(prefix + "ListUsers"), echo("x"));
        JsonNode d = Json.parse(docs.toJson(List.of(
            context("context-path-1", module.apply("cp1")),
            context("context-path-2", module.apply("cp2")))));
        assertEquals(sorted("cp1ListUsers", "cp2ListUsers"), sorted(d.findValuesAsText("operationId").toArray(new String[0])));
    }

    @Test
    void eachContextCanServeItsOwnDocumentInstead() throws Exception {
        MuServer separate = MuServerBuilder.httpServer()
            .addHandler(ContextHandlerBuilder.context("context-path-1").addHandler(docs.serve(usersAndSession)))
            .addHandler(ContextHandlerBuilder.context("context-path-2").addHandler(docs.serve(usersAndSession)))
            .start();
        try {
            for (String context : List.of("context-path-1", "context-path-2")) {
                JsonNode d = Json.parse(client.send(HttpRequest.newBuilder(separate.uri().resolve("/" + context + "/openapi.json")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
                assertEquals("/" + context, d.at("/servers/0/url").asText());
                assertEquals(sorted("/users", "/users/{id}", "/session"), keys(d.get("paths")));
                assertEquals("/" + context + " get-user {id=7}", client.send(HttpRequest.newBuilder(separate.uri().resolve("/" + context + "/users/7")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            }
        } finally {
            separate.stop();
        }
    }

    @Test
    void theDocumentFollowsHowContextsAreActuallyNested() throws Exception {
        // Mounting a documented context inside a plain mu-server context of the same name really does serve the
        // routes at /context-path-1/context-path-1/..., and the document says exactly that.
        MuServer nested = MuServerBuilder.httpServer()
            .addHandler(ContextHandlerBuilder.context("context-path-1")
                .addHandler(docs.serve(context("context-path-1", usersAndSession))))
            .start();
        try {
            JsonNode d = Json.parse(client.send(HttpRequest.newBuilder(nested.uri().resolve("/context-path-1/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("/context-path-1", d.at("/servers/0/url").asText());
            assertEquals(sorted("/context-path-1/users", "/context-path-1/users/{id}", "/context-path-1/session"), keys(d.get("paths")));
            assertEquals("/context-path-1/context-path-1 list-users {}", client.send(
                HttpRequest.newBuilder(nested.uri().resolve("/context-path-1/context-path-1/users")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            nested.stop();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve(path)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
