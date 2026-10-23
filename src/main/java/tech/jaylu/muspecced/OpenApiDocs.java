package tech.jaylu.muspecced;

import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuHandler;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.openapi.InfoObject;
import io.muserver.openapi.OpenAPIObject;
import io.muserver.openapi.OpenAPIObjectBuilder;
import io.muserver.openapi.OperationObject;
import io.muserver.openapi.OperationObjectBuilder;
import io.muserver.openapi.PathItemObject;
import io.muserver.openapi.TagObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.muserver.openapi.OpenAPIObjectBuilder.openAPIObject;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static io.muserver.openapi.TagObjectBuilder.tagObject;

/**
 * Builds an OpenAPI document from a list of {@link SpeccedHandler}s, and serves it.
 * <p>This is immutable and keeps no record of handlers: the document is always built from the list you pass in.
 * The easiest way to keep the document in sync with the server is {@link #serve(List)}, which returns a single
 * handler that runs the given handlers <em>and</em> serves their document, so the two cannot come from different
 * lists.</p>
 * <pre><code>
 * OpenApiDocs docs = OpenApiDocs.openApiDocs(infoObject().withTitle("Users API").withVersion("1.0").build())
 *     .withTag("users", "Manage users");
 * MuServer server = MuServerBuilder.httpServer()
 *     .addHandler(docs.serve(usersModule.handlers()))
 *     .start();
 * </code></pre>
 */
public final class OpenApiDocs {

    /** The path the document is served at unless changed with {@link #withJsonPath(String)} */
    public static final String DEFAULT_JSON_PATH = "/openapi.json";

    private final OpenAPIObject base;
    private final Map<String, TagObject> tags;
    private final String jsonPath;

    private OpenApiDocs(OpenAPIObject base, Map<String, TagObject> tags, String jsonPath) {
        this.base = base;
        this.tags = tags;
        this.jsonPath = jsonPath;
    }

    /**
     * @param info The API info, with at least a title and version
     * @return Documentation settings
     */
    public static OpenApiDocs openApiDocs(InfoObject info) {
        return openApiDocs(openAPIObject().withInfo(Objects.requireNonNull(info, "info")));
    }

    /**
     * Uses an existing OpenAPI object for the info, servers, components, security, tags and external docs. Its
     * paths are ignored; they come from the handlers.
     * <p>When no servers are given, the served document uses the request's context path as the server URL.</p>
     *
     * @param base An OpenAPI object builder with at least the info set
     * @return Documentation settings
     */
    public static OpenApiDocs openApiDocs(OpenAPIObjectBuilder base) {
        Objects.requireNonNull(base, "base");
        return new OpenApiDocs(base.withPaths(pathsObject().build()).build(), Collections.emptyMap(), DEFAULT_JSON_PATH);
    }

    /**
     * Describes a tag used by operations. Tags are listed in the order they are added, followed by any other tags
     * used by operations.
     *
     * @param name        The tag name
     * @param description The tag description
     * @return New settings
     */
    public OpenApiDocs withTag(String name, String description) {
        Map<String, TagObject> copy = new LinkedHashMap<>(tags);
        copy.put(name, tagObject().withName(name).withDescription(description).build());
        return new OpenApiDocs(base, Collections.unmodifiableMap(copy), jsonPath);
    }

    /**
     * @param jsonPath The path that {@link #serve(List)} serves the document at, relative to the context the
     *                 handler is added to. Defaults to {@value #DEFAULT_JSON_PATH}.
     * @return New settings
     */
    public OpenApiDocs withJsonPath(String jsonPath) {
        return new OpenApiDocs(base, tags, PathTemplate.join("", Objects.requireNonNull(jsonPath, "jsonPath")));
    }

    /**
     * Builds the document for the given handlers.
     * <p>Operations without an operation ID get one generated from the method and path, e.g.
     * <code>GET_v1_users__id_</code>.</p>
     *
     * @param handlers    The handlers to document
     * @param contextPath The context path the API is served under, used as the server URL when no servers were
     *                    given. Use an empty string for none.
     * @return The OpenAPI document
     * @throws IllegalArgumentException if two operations have the same method and path, or the same operation ID
     */
    public OpenAPIObject document(List<SpeccedHandler> handlers, String contextPath) {
        Objects.requireNonNull(handlers, "handlers");
        List<ApiOperation> operations = new ArrayList<>();
        for (SpeccedHandler handler : handlers) {
            operations.addAll(Objects.requireNonNull(handler, "handlers cannot contain null").apiOperations());
        }

        Map<String, ApiOperation> byShape = new HashMap<>();
        Map<String, ApiOperation> byOperationId = new HashMap<>();
        Map<String, Map<String, OperationObject>> operationsByPath = new LinkedHashMap<>();
        List<String> usedTags = new ArrayList<>();
        for (ApiOperation op : operations) {
            ApiOperation clash = byShape.put(op.method() + " " + op.shape(), op);
            if (clash != null) {
                throw new IllegalArgumentException(op + " is documented more than once (also as " + clash + ")");
            }
            OperationObject operation = op.operation();
            if (operation.operationId() == null) {
                operation = OperationObjectBuilder.builderFrom(operation).withOperationId(defaultOperationId(op)).build();
            }
            ApiOperation idClash = byOperationId.put(operation.operationId(), op);
            if (idClash != null) {
                throw new IllegalArgumentException("The operation ID '" + operation.operationId() + "' of " + op
                    + " is already used by " + idClash);
            }
            operationsByPath.computeIfAbsent(op.path(), k -> new LinkedHashMap<>())
                .put(op.method().name().toLowerCase(), operation);
            if (operation.tags() != null) {
                for (String tag : operation.tags()) {
                    if (!usedTags.contains(tag)) {
                        usedTags.add(tag);
                    }
                }
            }
        }

        Map<String, PathItemObject> pathItems = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, OperationObject>> entry : operationsByPath.entrySet()) {
            pathItems.put(entry.getKey(), pathItemObject().withOperations(entry.getValue()).build());
        }

        Map<String, TagObject> allTags = new LinkedHashMap<>();
        if (base.tags() != null) {
            for (TagObject tag : base.tags()) {
                allTags.put(tag.name(), tag);
            }
        }
        allTags.putAll(tags);
        for (String tag : usedTags) {
            if (!allTags.containsKey(tag)) {
                allTags.put(tag, tagObject().withName(tag).build());
            }
        }

        String context = PathTemplate.trimSlashes(contextPath);
        return openAPIObject()
            .withInfo(base.info())
            .withExternalDocs(base.externalDocs())
            .withSecurity(base.security())
            .withComponents(base.components())
            .withServers(base.servers() != null ? base.servers()
                : context.isEmpty() ? null : Collections.singletonList(serverObject().withUrl("/" + context).build()))
            .withPaths(pathsObject().withPathItemObjects(pathItems).build())
            .withTags(allTags.isEmpty() ? null : new ArrayList<>(allTags.values()))
            .build();
    }

    /**
     * Builds the document as JSON, e.g. to write it to a file during a build or to compare it in a test
     *
     * @param handlers The handlers to document
     * @return The OpenAPI document as JSON
     */
    public String toJson(List<SpeccedHandler> handlers) {
        return toJson(document(handlers, ""));
    }

    /**
     * Creates one handler that runs the given handlers in order and serves their OpenAPI document as JSON at the
     * {@link #withJsonPath(String) JSON path} for <code>GET</code> requests.
     * <p>The document is built once here, so duplicate operations fail at startup. The JSON is then cached for
     * each context path the handler is used under.</p>
     *
     * @param handlers The handlers to run and document
     * @return A handler to add to a server or context
     * @throws IllegalArgumentException if the handlers cannot be documented, e.g. because of duplicate operations
     */
    public MuHandler serve(List<SpeccedHandler> handlers) {
        List<SpeccedHandler> copy = Collections.unmodifiableList(new ArrayList<>(handlers));
        document(copy, ""); // fail fast
        MuHandler app = SpeccedHandler.combine(copy).handler();
        String target = PathTemplate.trimSlashes(jsonPath);
        Map<String, String> jsonByContext = new ConcurrentHashMap<>();
        return new MuHandler() {
            @Override
            public boolean handle(MuRequest request, MuResponse response) throws Exception {
                if (request.method() == Method.GET && PathTemplate.trimSlashes(request.relativePath()).equals(target)) {
                    String json = jsonByContext.computeIfAbsent(request.contextPath(), cp -> toJson(document(copy, cp)));
                    response.contentType(ContentTypes.APPLICATION_JSON);
                    response.write(json);
                    return true;
                }
                return app.handle(request, response);
            }

            @Override
            public String toString() {
                return "OpenApiDocsHandler{jsonPath='/" + target + "', handlers=" + copy + "}";
            }
        };
    }

    /**
     * @param handlers The handlers to run and document
     * @return A handler to add to a server or context
     * @see #serve(List)
     */
    public MuHandler serve(SpeccedHandler... handlers) {
        return serve(Arrays.asList(handlers));
    }

    private static String defaultOperationId(ApiOperation op) {
        String opPath = PathTemplate.trimSlashes(op.path().replace("{", "_").replace("}", "_")).replace("/", "_");
        return op.method().name() + "_" + opPath;
    }

    private static String toJson(OpenAPIObject document) {
        StringWriter writer = new StringWriter();
        try {
            document.writeJson(writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writer.toString();
    }
}
