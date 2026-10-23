package tech.jaylu.muspecced;

import io.muserver.Method;
import io.muserver.RouteHandler;
import io.muserver.Routes;
import io.muserver.openapi.OperationObject;
import io.muserver.openapi.SchemaObject;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Settings for creating documented routes: how types become schemas, and whether strict mode is on.
 * <p>This is immutable and records nothing. Each <code>with...</code> method returns a new instance, so one
 * instance can be shared freely, for example as a constant.</p>
 * <pre><code>
 * static final OpenApiRoutes routes = OpenApiRoutes.openApiRoutes()
 *     .withStrictMode(true)
 *     .withSchema(Money.class, moneySchema);
 *
 * SpeccedHandler getUser = routes.route(GET, "/users/{id}", op -&gt; ..., users::get);
 * </code></pre>
 */
public final class OpenApiRoutes {

    static final OpenApiRoutes DEFAULT = new OpenApiRoutes(new ReflectionSchemaProvider(), Collections.emptyMap(), false);

    private final SchemaProvider schemaProvider;
    private final Map<Class<?>, SchemaObject> customSchemas;
    private final boolean strict;
    private final SchemaProvider schemas;

    private OpenApiRoutes(SchemaProvider schemaProvider, Map<Class<?>, SchemaObject> customSchemas, boolean strict) {
        this.schemaProvider = schemaProvider;
        this.customSchemas = customSchemas;
        this.strict = strict;
        // Custom schemas apply to nested types when the default provider is used, and at the top level otherwise
        SchemaProvider provider = schemaProvider instanceof ReflectionSchemaProvider && !customSchemas.isEmpty()
            ? new ReflectionSchemaProvider(customSchemas) : schemaProvider;
        this.schemas = type -> {
            Type unwrapped = TypeRef.unwrap(type);
            SchemaObject custom = unwrapped instanceof Class ? customSchemas.get(unwrapped) : null;
            return custom != null ? custom : provider.schemaFor(unwrapped);
        };
    }

    /**
     * @return Settings with the {@link ReflectionSchemaProvider} and strict mode off
     */
    public static OpenApiRoutes openApiRoutes() {
        return DEFAULT;
    }

    /**
     * In strict mode, creating a route or operation with no summary or no documented responses fails.
     *
     * @param strict True to turn on strict mode
     * @return New settings
     */
    public OpenApiRoutes withStrictMode(boolean strict) {
        return new OpenApiRoutes(schemaProvider, customSchemas, strict);
    }

    /**
     * Replaces the {@link ReflectionSchemaProvider}. Schemas given to {@link #withSchema(Class, SchemaObject)}
     * still take precedence for top-level types.
     *
     * @param schemaProvider The provider to use
     * @return New settings
     */
    public OpenApiRoutes withSchemaProvider(SchemaProvider schemaProvider) {
        return new OpenApiRoutes(Objects.requireNonNull(schemaProvider, "schemaProvider"), customSchemas, strict);
    }

    /**
     * Uses the given schema whenever the class is documented, instead of generating one.
     *
     * @param type   The class
     * @param schema The schema to use for it
     * @return New settings
     */
    public OpenApiRoutes withSchema(Class<?> type, SchemaObject schema) {
        Map<Class<?>, SchemaObject> copy = new HashMap<>(customSchemas);
        copy.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(schema, "schema"));
        return new OpenApiRoutes(schemaProvider, Collections.unmodifiableMap(copy), strict);
    }

    /**
     * Creates a route handler, as {@link Routes#route(Method, String, RouteHandler)} does, paired with its
     * documentation.
     * <p>The documentation is checked against the URI template now, so mistakes fail at startup. For example,
     * documenting a path parameter that is not in the template fails, and parameters in the template that are not
     * documented are added as strings.</p>
     *
     * @param method      The HTTP method. Unlike {@link Routes#route(Method, String, RouteHandler)}, null is not
     *                    allowed, because an operation is documented per method.
     * @param uriTemplate The URI template, e.g. <code>/users/{id : [0-9]+}</code>
     * @param doc         A callback that documents the operation
     * @param handler     The handler to call when the route matches
     * @return A specced handler with one operation
     * @throws IllegalArgumentException if the documentation does not match the route
     */
    public SpeccedHandler route(Method method, String uriTemplate, Consumer<OperationDoc> doc, RouteHandler handler) {
        Objects.requireNonNull(handler, "handler");
        ApiOperation operation = operation(method, uriTemplate, doc);
        return SpeccedHandler.specced(Routes.route(method, uriTemplate, handler), operation);
    }

    /**
     * Documents an operation using the {@link OperationDoc} DSL, without creating a handler. Use this for
     * hand-written handlers, then pair them with {@link SpeccedHandler#specced(io.muserver.MuHandler, ApiOperation...)}.
     *
     * @param method      The HTTP method
     * @param uriTemplate The URI template or OpenAPI path, e.g. <code>/users/{id}/avatar</code>
     * @param doc         A callback that documents the operation
     * @return The operation
     * @throws IllegalArgumentException if the documentation does not match the path
     */
    public ApiOperation operation(Method method, String uriTemplate, Consumer<OperationDoc> doc) {
        ApiOperation.checkMethod(method);
        Objects.requireNonNull(doc, "doc");
        PathTemplate path = PathTemplate.parse("", uriTemplate);
        OperationDoc operationDoc = new OperationDoc(method, path);
        doc.accept(operationDoc);

        String where = method + " " + path.openApiPath;
        if (strict) {
            if (operationDoc.summaryValue() == null || operationDoc.summaryValue().trim().isEmpty()) {
                throw new IllegalArgumentException("Strict mode: " + where + " has no summary");
            }
            if (!operationDoc.hasResponses()) {
                throw new IllegalArgumentException("Strict mode: " + where + " has no documented responses");
            }
        }
        OperationObject operation = operationDoc.build(schemas);
        return ApiOperation.trusted(method, path, operation);
    }

    /**
     * @return The schema provider used by these settings, including any custom schemas
     */
    public SchemaProvider schemas() {
        return schemas;
    }
}
