# mu-specced-handlers

OpenAPI documentation for [Mu Server](https://muserver.io) functional-style handlers, built on the
`SpeccedHandler` design suggested by the mu-server author:

```
data class SpeccedHandler(MuHandler handler, List<ApiOperation> apiOperations)
```

A helper returns a **value** that holds a handler together with the OpenAPI operations it serves. Nothing is
registered anywhere. You build a list of these values, and that one list is used both to run the handlers and to
build the OpenAPI document, so the document matches what is mounted.

- Works with any `MuHandler`, not just `Routes.route(...)`. One handler can document several operations.
- Values compose: modules return lists of handlers, `combine(...)` merges them, and `context(...)` mounts them
  under a path and prefixes their documented paths.
- Documentation is checked when it is created, against the URI template, so mistakes fail at startup.
- No global or mutable state. Everything can be unit tested without starting a server.
- Depends only on `mu-server`, and targets Java 8 like mu-server 2.x.

## Contents

- [Quick start](#quick-start)
- [Concepts](#concepts)
- [Documenting operations](#documenting-operations)
- [Composing handlers](#composing-handlers)
- [Settings](#settings)
- [What is checked](#what-is-checked)
- [Testing your documentation](#testing-your-documentation)
- [Request and response bodies](#request-and-response-bodies)
- [Known limitations](#known-limitations-mu-server-232)
- [Building](#building)

## Quick start

```java
import static io.github.jaylu.muspecced.SpeccedHandler.route;
import static io.muserver.Method.*;
import static io.muserver.openapi.InfoObjectBuilder.infoObject;

List<SpeccedHandler> api = Arrays.asList(
    route(GET, "/users/{id : [0-9]+}",
        op -> op.summary("Get a user")
            .pathParam("id", long.class, "The user ID")
            .response(200, User.class, "The user")
            .response(404, "No user has this ID"),
        (req, resp, params) -> { /* handler */ }),
    route(POST, "/users",
        op -> op.summary("Create a user")
            .requestBody(NewUser.class, "The user to create")
            .response(201, User.class, "The created user"),
        (req, resp, params) -> { /* handler */ }));

OpenApiDocs docs = OpenApiDocs.openApiDocs(infoObject().withTitle("Users API").withVersion("1.0").build());

MuServer server = MuServerBuilder.httpServer()
    .addHandler(docs.serve(api))   // runs the handlers AND serves /openapi.json for them
    .start();
```

`docs.serve(api)` returns one `MuHandler`. It answers `GET /openapi.json` and passes every other request to the
handlers in order. Because the handlers and the document come from the same call, they cannot drift apart.

## Concepts

| Type | What it is |
|---|---|
| `SpeccedHandler` | An immutable pair of a `MuHandler` and the `List<ApiOperation>` it serves. |
| `ApiOperation` | An HTTP method, an OpenAPI path such as `/users/{id}`, and mu-server's `OperationObject`. An `OperationObject` alone has no path or method, because OpenAPI uses those as the keys around it, so this class keeps them together. |
| `OperationDoc` | The fluent DSL used to describe an operation: parameters, bodies, responses and so on. |
| `OpenApiRoutes` | Immutable settings (schema generation, strict mode) with `route(...)` and `operation(...)` helpers. `SpeccedHandler.route(...)` uses the defaults. |
| `OpenApiDocs` | Immutable document settings (info, tags, JSON path). It assembles a document from a list of handlers and serves it. |
| `SchemaProvider` / `ReflectionSchemaProvider` | Turns Java types into OpenAPI schemas. |
| `TypeRef<T>` | Captures generic types such as `new TypeRef<List<User>>() {}`. |

## Documenting operations

`route(method, uriTemplate, doc, handler)` creates the handler with `Routes.route(...)`, so routing is exactly as in
plain mu-server, and documents it with the `doc` callback. Every type argument is a `java.lang.reflect.Type`: pass
a class like `User.class`, or a `TypeRef` for generic types.

### GET with query and header parameters

```java
route(GET, "/users",
    op -> op.summary("List users").tag("users")
        .queryParam("limit", int.class, "Maximum number of users", 20)        // optional, with a default
        .queryParam("role", Role.class, "Only users with this role")          // enum -> string with enum values
        .queryParam("tag", new TypeRef<List<String>>() {}, "Filter by tags")  // repeated parameter -> array
        .requiredQueryParam("q", String.class, "Search text")
        .headerParam("X-Request-Id", String.class, "Used for tracing")
        .cookieParam("session", String.class, "Session cookie")
        .response(200, new TypeRef<Page<User>>() {}, "A page of users"),      // generics are resolved
    handler)
```

### Path parameters

```java
route(GET, "/users/{id : [0-9]+}",
    op -> op.pathParam("id", long.class, "The user ID").response(200, User.class, "The user"),
    handler)
```

- The documented path is `/users/{id}`; the regex is used for matching only.
- When the parameter type is a string, the regex is added to the schema as `pattern`.
- Path parameters you don't document are added automatically as required strings.
- Documenting a path parameter that is not in the template fails.

### POST with a JSON body, an example and a response header

```java
route(POST, "/users",
    op -> op.summary("Create a user")
        .requestBody(OperationDoc.JSON, NewUser.class, "The user to create",
            Map.of("name", "Ada", "email", "ada@example.org"))               // example as a Map
        .response(201, User.class, "The created user")
        .responseHeader(201, "Location", String.class, "The URL of the new user")
        .response(400, "application/problem+json", Problem.class, "The body is not valid"),
    handler)
```

### PUT, PATCH and DELETE

```java
route(PUT, "/users/{id}", op -> op.requestBody(NewUser.class, "New details").response(200, User.class, "Updated"), handler);
route(PATCH, "/users/{id}", op -> op.requestBody("application/merge-patch+json", UserPatch.class, "Fields to change")
    .response(200, User.class, "Updated"), handler);
route(DELETE, "/users/{id}", op -> op.response(204, "Deleted"), handler);   // no body
```

### Other media types

```java
op.requestBody("text/plain", String.class, "Any text")
  .response(200, "text/plain", String.class, "The same text");

op.requestBody("image/png", byte[].class, "A PNG image")     // byte[], InputStream and File -> binary
  .requestBody("image/jpeg", byte[].class, null);             // call again to add more media types
```

### Other options

```java
op.description("Longer **CommonMark** description")
  .operationId("getUser")     // otherwise generated from the final path, e.g. GET_v1_users__id_
  .tag("users")
  .deprecated()
  .requestBodyOptional()
  .parameter(parameterObject()...build())                 // any ParameterObject
  .customize(b -> b.withSecurity(...));                    // direct access to OperationObjectBuilder
```

### Hand-written handlers

Any `MuHandler` can be documented. Describe its operations with `OpenApiRoutes.operation(...)`, which uses the same
DSL and checks, then pair them with the handler:

```java
static final OpenApiRoutes routes = OpenApiRoutes.openApiRoutes();

static SpeccedHandler avatarHandler() {
    MuHandler handler = (req, resp) -> { /* handles PUT and DELETE on /users/{id}/avatar */ };
    return SpeccedHandler.specced(handler,
        routes.operation(PUT, "/users/{id : [0-9]+}/avatar",
            op -> op.summary("Upload an avatar").requestBody("image/png", byte[].class, "A PNG")
                    .response(204, "Saved")),
        routes.operation(DELETE, "/users/{id : [0-9]+}/avatar",
            op -> op.summary("Remove the avatar").response(204, "Removed")));
}
```

You can also build an `OperationObject` with mu-server's builders and wrap it with
`ApiOperation.apiOperation(method, path, operationObject)`. This checks that the path parameters in the path and the
operation match, as OpenAPI requires.

Note that the operations of a hand-written handler are **not** checked against what the handler really matches.
Prefer `route(...)` where you can.

### Handlers that should not be documented

```java
SpeccedHandler.specced(staticFilesHandler)   // no operations: served but not in the document
```

## Composing handlers

```java
// A module exposes its handlers as values
class UsersModule {
    List<SpeccedHandler> handlers() { return Arrays.asList(listUsers(), getUser(), createUser()); }
}

List<SpeccedHandler> app = Arrays.asList(
    SpeccedHandler.combine(users.handlers()),                // many handlers as one
    SpeccedHandler.context("v1", status()),                  // documented as /v1/status
    SpeccedHandler.context("api",                            // contexts can be nested: /api/v2/...
        SpeccedHandler.context("v2", v2.handlers())),
    SpeccedHandler.specced(health()));                       // not documented

server.addHandler(docs.serve(app));
```

- `combine(...)` tries each handler in order and keeps all their operations.
- `context(path, ...)` wraps the handlers in a mu-server `ContextHandler`, so they see paths relative to the context,
  and adds the context path to each operation's documented path.
- The same module can be mounted in two contexts. Generated operation IDs use the final path, so they stay unique
  (e.g. `GET_v1_status` and `GET_v2_status`).

### Several context paths in one document

To serve the same handlers under several contexts, for example `/context-path-1/users`,
`/context-path-1/session`, `/context-path-2/users` and `/context-path-2/session`, mount the same list twice.
Specced handlers are immutable values, so the module needs no knowledge of contexts and can be reused as it is:

```java
static final List<SpeccedHandler> usersAndSession = Arrays.asList(
    route(GET, "/users", op -> op.summary("List users").tag("users"), listUsers),
    route(GET, "/users/{id : [0-9]+}", op -> ..., getUser),
    route(POST, "/session", op -> op.summary("Log in").tag("session"), login),
    route(DELETE, "/session", op -> ..., logout));

server.addHandler(docs.serve(
    context("context-path-1", usersAndSession),
    context("context-path-2", usersAndSession)));         // one document at /openapi.json for both
```

The result is a single document with all six paths:

| | `context-path-1` | `context-path-2` |
|---|---|---|
| Paths | `/context-path-1/users`, `/context-path-1/users/{id}`, `/context-path-1/session` | the same under `/context-path-2` |
| Generated operation IDs | `GET_context-path-1_users`, … | `GET_context-path-2_users`, … |
| Tags | `users` and `session` are listed once and shared by both contexts | |

Things to know:

- **Explicit operation IDs must be unique across contexts.** If the module sets `operationId("listUsers")`,
  `serve(...)` fails at startup with `The operation ID 'listUsers' of GET /context-path-2/users is already used
  by GET /context-path-1/users`. Either leave operation IDs unset, so they are generated from the full path, or
  make the module a method that takes an ID prefix.
- **The document follows the real nesting.** Each documented path is built from the same `context(...)` calls
  that do the routing, so the docs can't show a different prefix from the one mounted. If `docs.serve(...)` is
  itself added inside a plain mu-server context, that context becomes the server URL.
- **One document per context** is also possible: add `docs.serve(usersAndSession)` inside each
  `ContextHandlerBuilder.context("context-path-N")`. Each document then lists `/context-path-N` as its server and
  paths such as `/users`.

`MultipleContextsTest` covers all of these cases against a running server.

### Without `serve(...)`

You don't have to use `serve(...)`. You can add `handler()` of each `SpeccedHandler` yourself and build the
document with `docs.document(list, contextPath)` or `docs.toJson(list)`. But then you must pass the same list to
both, which is exactly what `serve(...)` guarantees.

## Settings

### `OpenApiRoutes`: how operations are created

```java
static final OpenApiRoutes routes = OpenApiRoutes.openApiRoutes()
    .withStrictMode(true)                              // every operation needs a summary and a response
    .withSchema(Money.class, schemaObject().withType("string").withDescription("e.g. 12.50 GBP").build())
    .withSchemaProvider(myProvider);                   // replace the reflection-based provider

SpeccedHandler h = routes.route(GET, "/orders/{id}", op -> ..., handler);
```

Every `with...` method returns a new instance, so settings can be shared as constants.
`SpeccedHandler.route(...)` uses the defaults: reflection schemas and strict mode off.

### `OpenApiDocs`: the document

```java
OpenApiDocs docs = OpenApiDocs.openApiDocs(openAPIObject()      // or openApiDocs(InfoObject)
        .withInfo(info)
        .withServers(servers)                                     // optional
        .withComponents(components)                               // e.g. security schemes
        .withSecurity(security))
    .withTag("users", "Manage users")
    .withJsonPath("/docs/openapi.json");                          // default /openapi.json
```

When no servers are given, the served document uses the request's context path as the server URL. For example,
`context("api").addHandler(docs.serve(...))` lists `/api` as the server.

### Schemas

`ReflectionSchemaProvider` describes types with no extra dependencies:

| Java type | Schema |
|---|---|
| `String`, `char` | `string` |
| `int`, `short`, `byte` / `long` | `integer` with `int32` / `int64` |
| `float` / `double`, `BigDecimal` | `number` with `float` / `double`, or plain `number` |
| `boolean` | `boolean` |
| `Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime`, `Date` / `LocalDate` | `string` with `date-time` / `date` |
| `UUID`, `URI` | `string` with `uuid`, `uri` |
| `byte[]`, `InputStream`, `File` | `string` with `binary` |
| enums | `string` with `enum` |
| arrays, collections (`Set` adds `uniqueItems`) | `array` with `items` |
| `Map<String, V>` | `object` with `additionalProperties` |
| `Optional<T>` | the schema of `T` |
| records | `object` with one property per component |
| other classes | `object` with public fields and getters, including inherited ones |

Type variables are resolved, so `Page<User>` describes `items` as an array of `User`. A type that refers to itself
is described as a plain object at the point of recursion.

## What is checked

| Check | When |
|---|---|
| A documented path parameter is not in the URI template | Creating the route or operation |
| The same parameter (name and location) is documented twice | Creating the route or operation |
| A response header is documented for a status with no response | Creating the route or operation |
| An invalid status code, a bad URI template, a `null` or `CONNECT` method | Creating the route or operation |
| In strict mode: no summary, or no documented responses | Creating the route or operation |
| `ApiOperation.apiOperation(...)`: path params in the path and the operation differ | Creating the operation |
| Two operations with the same method and path, even with different parameter names | `serve(...)` / `document(...)` |
| Two operations with the same operation ID | `serve(...)` / `document(...)` |

`serve(...)` builds the document immediately, so every check fails before the server starts.

## Testing your documentation

Because handlers are values, you can check their documentation without a server:

```java
@Test
void getUserIsDocumented() {
    ApiOperation op = UsersModule.getUser().apiOperations().get(0);
    assertEquals("/users/{id}", op.path());
    assertEquals("integer", op.operation().parameters().get(0).schema().type());
}
```

Other useful checks:

- **Snapshot test.** Write `docs.toJson(app)` to a file and compare it in a test, so every API change shows up
  in code review. See the ordering limitation below.
- **Validate the spec.** The tests in this project use `swagger-parser` to check that the generated document
  is valid OpenAPI 3.
- **Explore the sample.** `src/test/java/io/github/jaylu/muspecced/SampleApi.java` is a complete example
  covering GET, POST, PUT, PATCH, DELETE, a hand-written multi-operation handler, an undocumented handler and a
  context. Running the tests writes its document to `target/sample-openapi.json`.

## Request and response bodies

This library documents bodies; it does not read or write them. Your handler still parses the body itself, for
example with `req.readBodyAsString()` and Jackson, so make sure the documented type is the type you parse into.

A natural next step is a small `BodyCodec` (for example backed by Jackson), so that a typed route parses the body
into the same class it documents:

```java
// Possible future API
routes.route(POST, "/users", NewUser.class, op -> ..., (req, resp, params, body) -> { ... });
```

## Known limitations (mu-server 2.3.2)

These come from mu-server's OpenAPI model, and could be fixed there:

- **Order is not kept.** `OpenApiUtils.immutable` copies maps into a `HashMap`, so paths, properties, media types
  and responses are not written in declaration order. Using a `LinkedHashMap` would fix it.
- **No `$ref`.** `SchemaObject` cannot express `$ref`, so DTO schemas are inlined and repeated.
- **Enum values must be Java enums.** `SchemaObject` writes `enum` values with `Enum.name()`, so a list of plain
  strings cannot be given.
- **Examples need maps or lists.** Examples are written by mu-server's `Jsonizer`, which supports strings,
  numbers, booleans, lists and maps; other objects are written using `toString()`.
- **JSON only.** There is no HTML page, because mu-server's `HtmlDocumentor` is package-private. Point Swagger UI
  or Redoc at the JSON.

## Building

The library targets Java 8. Building needs JDK 17 or later, because the tests use records and `HttpClient`.

```
mvn test
```

The tests start real servers on random ports and use JUnit 5, Jackson and swagger-parser. These are test
dependencies only.

## Using it in your project

The library is not on Maven Central yet. Install it into your local Maven repository with `mvn install`, then
add:

```xml
<dependency>
    <groupId>tech.jaylu</groupId>
    <artifactId>mu-specced-handlers</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

It depends on `io.muserver:mu-server` 2.3.2 or later in the 2.x line.

## License

[MIT](LICENSE)
