package tech.jaylu.muspecced;

import io.muserver.MuHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tech.jaylu.muspecced.SpeccedHandler.context;
import static tech.jaylu.muspecced.SpeccedHandler.specced;
import static io.muserver.Method.DELETE;
import static io.muserver.Method.GET;
import static io.muserver.Method.PATCH;
import static io.muserver.Method.POST;
import static io.muserver.Method.PUT;
import static io.muserver.openapi.InfoObjectBuilder.infoObject;

/**
 * A small users API used by the tests, written the way an application would use the library: each module returns
 * its handlers as values, and the application decides what to mount.
 * Bodies are parsed and written by hand with org.json, as a functional handler would do without a codec.
 */
class SampleApi {

    enum Role {ADMIN, MEMBER}

    record Address(String street, String city, String postcode) {}

    record User(long id, String name, String email, Role role, List<String> tags, Address address, Instant createdAt) {}

    record NewUser(String name, String email, Role role, List<String> tags, Address address) {}

    record Page<T>(List<T> items, int total, String next) {}

    record Problem(String title, int status, String detail) {}

    /** A plain class with getters, to check that non-record bodies are described too */
    public static class UserPatch {
        private String name;
        private Role role;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Role getRole() {
            return role;
        }

        public void setRole(Role role) {
            this.role = role;
        }
    }

    static final String PROBLEM_JSON = "application/problem+json";

    /** Shared settings; immutable, so a constant is fine */
    static final OpenApiRoutes routes = OpenApiRoutes.openApiRoutes().withStrictMode(true);

    static final OpenApiDocs docs = OpenApiDocs.openApiDocs(infoObject().withTitle("Users API").withVersion("1.0").build())
        .withTag("users", "Manage users");

    /** Everything the application mounts. The same list is used for serving and for the document. */
    static List<SpeccedHandler> all(UsersModule users) {
        return Arrays.asList(
            SpeccedHandler.combine(users.handlers()),
            avatarHandler(),
            echo(),
            context("v1", status()),
            specced(health())   // served, but deliberately not documented
        );
    }

    static final class UsersModule {
        private final Map<Long, JSONObject> users = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        List<SpeccedHandler> handlers() {
            return Arrays.asList(
                routes.route(GET, "/users",
                    op -> op.summary("List users").tag("users")
                        .queryParam("limit", int.class, "Maximum number of users to return", 20)
                        .queryParam("role", Role.class, "Only return users with this role")
                        .queryParam("tag", new TypeRef<List<String>>() {}, "Only return users with all of these tags")
                        .headerParam("X-Request-Id", String.class, "Echoed in the response for tracing")
                        .response(200, new TypeRef<Page<User>>() {}, "A page of users"),
                    (req, resp, params) -> {
                        int limit = req.query().getInt("limit", 20);
                        JSONArray items = new JSONArray();
                        users.values().stream().limit(limit).forEach(items::put);
                        resp.contentType("application/json");
                        resp.write(new JSONObject().put("items", items).put("total", users.size()).toString());
                    }),
                routes.route(GET, "/users/{id : [0-9]+}",
                    op -> op.summary("Get a user").tag("users")
                        .pathParam("id", long.class, "The user ID")
                        .response(200, User.class, "The user")
                        .response(404, PROBLEM_JSON, Problem.class, "No user has this ID"),
                    (req, resp, params) -> {
                        JSONObject user = users.get(Long.parseLong(params.get("id")));
                        if (user == null) {
                            notFound(resp);
                        } else {
                            resp.contentType("application/json");
                            resp.write(user.toString());
                        }
                    }),
                routes.route(POST, "/users",
                    op -> op.summary("Create a user").tag("users")
                        .requestBody(OperationDoc.JSON, NewUser.class, "The user to create",
                            Map.of("name", "Ada", "email", "ada@example.org", "role", "MEMBER"))
                        .response(201, User.class, "The created user")
                        .responseHeader(201, "Location", String.class, "The URL of the created user")
                        .response(400, PROBLEM_JSON, Problem.class, "The body is not valid"),
                    (req, resp, params) -> {
                        JSONObject body = new JSONObject(req.readBodyAsString());
                        long id = ids.incrementAndGet();
                        JSONObject user = new JSONObject(body.toMap()).put("id", id).put("createdAt", Instant.now().toString());
                        users.put(id, user);
                        resp.status(201);
                        resp.headers().set("Location", "/users/" + id);
                        resp.contentType("application/json");
                        resp.write(user.toString());
                    }),
                routes.route(PUT, "/users/{id : [0-9]+}",
                    op -> op.summary("Replace a user").tag("users")
                        .pathParam("id", long.class, "The user ID")
                        .requestBody(NewUser.class, "The new user details")
                        .response(200, User.class, "The updated user")
                        .response(404, PROBLEM_JSON, Problem.class, "No user has this ID"),
                    (req, resp, params) -> {
                        long id = Long.parseLong(params.get("id"));
                        if (!users.containsKey(id)) {
                            notFound(resp);
                            return;
                        }
                        JSONObject user = new JSONObject(req.readBodyAsString()).put("id", id);
                        users.put(id, user);
                        resp.contentType("application/json");
                        resp.write(user.toString());
                    }),
                routes.route(PATCH, "/users/{id : [0-9]+}",
                    op -> op.summary("Update some fields of a user").tag("users")
                        .pathParam("id", long.class, "The user ID")
                        .requestBody("application/merge-patch+json", UserPatch.class, "The fields to change")
                        .response(200, User.class, "The updated user")
                        .response(404, PROBLEM_JSON, Problem.class, "No user has this ID"),
                    (req, resp, params) -> {
                        JSONObject user = users.get(Long.parseLong(params.get("id")));
                        if (user == null) {
                            notFound(resp);
                            return;
                        }
                        JSONObject patch = new JSONObject(req.readBodyAsString());
                        for (String key : patch.keySet()) {
                            user.put(key, patch.get(key));
                        }
                        resp.contentType("application/json");
                        resp.write(user.toString());
                    }),
                routes.route(DELETE, "/users/{id : [0-9]+}",
                    op -> op.summary("Delete a user").tag("users")
                        .pathParam("id", long.class, "The user ID")
                        .response(204, "The user was deleted"),
                    (req, resp, params) -> {
                        users.remove(Long.parseLong(params.get("id")));
                        resp.status(204);
                    })
            );
        }
    }

    /**
     * A hand-written handler that serves two operations. The id path param is not documented, so it is added
     * automatically with the template's regex.
     */
    static SpeccedHandler avatarHandler() {
        Pattern path = Pattern.compile("/users/([0-9]+)/avatar");
        MuHandler handler = (req, resp) -> {
            Matcher m = path.matcher(req.relativePath());
            if (!m.matches() || (req.method() != PUT && req.method() != DELETE)) {
                return false;
            }
            resp.status(204);
            return true;
        };
        return specced(handler,
            routes.operation(PUT, "/users/{id : [0-9]+}/avatar",
                op -> op.summary("Upload an avatar").tag("users")
                    .requestBody("image/png", byte[].class, "A PNG image")
                    .requestBody("image/jpeg", byte[].class, null)
                    .response(204, "The avatar was saved")),
            routes.operation(DELETE, "/users/{id : [0-9]+}/avatar",
                op -> op.summary("Remove the avatar").tag("users")
                    .response(204, "The avatar was removed")));
    }

    static SpeccedHandler echo() {
        return routes.route(POST, "/echo",
            op -> op.summary("Echo text")
                .requestBody("text/plain", String.class, "Any text")
                .response(200, "text/plain", String.class, "The same text"),
            (req, resp, params) -> {
                resp.contentType("text/plain;charset=utf-8");
                resp.write(req.readBodyAsString());
            });
    }

    static SpeccedHandler status() {
        return routes.route(GET, "/status",
            op -> op.summary("Get the service status").deprecated()
                .response(200, "text/plain", String.class, "The status"),
            (req, resp, params) -> resp.write("OK"));
    }

    static MuHandler health() {
        return (req, resp) -> {
            if (!req.relativePath().equals("/health")) {
                return false;
            }
            resp.write("healthy");
            return true;
        };
    }

    private static void notFound(io.muserver.MuResponse resp) {
        resp.status(404);
        resp.contentType(PROBLEM_JSON);
        resp.write(new JSONObject().put("title", "Not Found").put("status", 404).toString());
    }
}
