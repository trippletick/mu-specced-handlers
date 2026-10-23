package tech.jaylu.muspecced;

import io.muserver.ContextHandlerBuilder;
import io.muserver.Method;
import io.muserver.MuHandler;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.RouteHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link MuHandler} together with the OpenAPI operations it serves.
 * <p>This is a plain immutable value: creating one registers nothing anywhere. Build a list of them, then use
 * {@link OpenApiDocs#serve(List)} to get one handler that runs them all and serves the OpenAPI document built from
 * the same list, so the document always matches what is mounted.</p>
 * <pre><code>
 * List&lt;SpeccedHandler&gt; api = Arrays.asList(
 *     SpeccedHandler.route(GET, "/users/{id : [0-9]+}",
 *         op -&gt; op.summary("Get a user").pathParam("id", long.class, "The user ID")
 *                 .response(200, User.class, "The user"),
 *         users::get),
 *     SpeccedHandler.context("v1", statusHandlers));
 * server.addHandler(OpenApiDocs.openApiDocs(info).serve(api));
 * </code></pre>
 */
public final class SpeccedHandler {

    private final MuHandler handler;
    private final List<ApiOperation> apiOperations;

    private SpeccedHandler(MuHandler handler, List<ApiOperation> apiOperations) {
        this.handler = Objects.requireNonNull(handler, "handler");
        for (ApiOperation op : apiOperations) {
            Objects.requireNonNull(op, "apiOperations cannot contain null");
        }
        this.apiOperations = Collections.unmodifiableList(new ArrayList<>(apiOperations));
    }

    /**
     * Pairs any handler with the operations it serves. Use this for hand-written handlers.
     * <p>The operations are not checked against what the handler really does, so prefer
     * {@link #route(Method, String, Consumer, RouteHandler)} where possible.</p>
     *
     * @param handler       The handler
     * @param apiOperations The operations the handler serves. Use none for a handler that should not appear in the
     *                      documentation, such as a static file handler.
     * @return A new specced handler
     */
    public static SpeccedHandler specced(MuHandler handler, ApiOperation... apiOperations) {
        return new SpeccedHandler(handler, Arrays.asList(apiOperations));
    }

    /**
     * Pairs any handler with the operations it serves.
     *
     * @param handler       The handler
     * @param apiOperations The operations the handler serves
     * @return A new specced handler
     */
    public static SpeccedHandler specced(MuHandler handler, List<ApiOperation> apiOperations) {
        return new SpeccedHandler(handler, Objects.requireNonNull(apiOperations, "apiOperations"));
    }

    /**
     * Creates a documented route using the default {@link OpenApiRoutes} settings. See
     * {@link OpenApiRoutes#route(Method, String, Consumer, RouteHandler)}.
     *
     * @param method      The HTTP method
     * @param uriTemplate The URI template, e.g. <code>/users/{id : [0-9]+}</code>
     * @param doc         A callback that documents the operation
     * @param handler     The handler to call when the route matches
     * @return A specced handler with one operation
     */
    public static SpeccedHandler route(Method method, String uriTemplate, Consumer<OperationDoc> doc, RouteHandler handler) {
        return OpenApiRoutes.DEFAULT.route(method, uriTemplate, doc, handler);
    }

    /**
     * Combines handlers into one. Requests are offered to each handler in order until one handles it, and the
     * operations of all of them are kept.
     *
     * @param handlers The handlers to combine
     * @return A specced handler
     */
    public static SpeccedHandler combine(List<SpeccedHandler> handlers) {
        List<SpeccedHandler> copy = new ArrayList<>(Objects.requireNonNull(handlers, "handlers"));
        List<MuHandler> muHandlers = new ArrayList<>();
        List<ApiOperation> operations = new ArrayList<>();
        for (SpeccedHandler h : copy) {
            Objects.requireNonNull(h, "handlers cannot contain null");
            muHandlers.add(h.handler);
            operations.addAll(h.apiOperations);
        }
        return new SpeccedHandler(new ChainHandler(muHandlers), operations);
    }

    /**
     * @param handlers The handlers to combine
     * @return A specced handler
     * @see #combine(List)
     */
    public static SpeccedHandler combine(SpeccedHandler... handlers) {
        return combine(Arrays.asList(handlers));
    }

    /**
     * Mounts handlers under a context path, as {@link ContextHandlerBuilder#context(String)} does, and adds the
     * context path to the documented path of each operation.
     *
     * @param path     The context path, e.g. <code>v1</code>
     * @param children The handlers to mount
     * @return A specced handler
     */
    public static SpeccedHandler context(String path, List<SpeccedHandler> children) {
        Objects.requireNonNull(path, "path");
        ContextHandlerBuilder context = ContextHandlerBuilder.context(path);
        List<ApiOperation> operations = new ArrayList<>();
        for (SpeccedHandler child : children) {
            Objects.requireNonNull(child, "children cannot contain null");
            context.addHandler(child.handler);
            for (ApiOperation op : child.apiOperations) {
                operations.add(op.withPathPrefix(path));
            }
        }
        return new SpeccedHandler(context.build(), operations);
    }

    /**
     * @param path     The context path, e.g. <code>v1</code>
     * @param children The handlers to mount
     * @return A specced handler
     * @see #context(String, List)
     */
    public static SpeccedHandler context(String path, SpeccedHandler... children) {
        return context(path, Arrays.asList(children));
    }

    /**
     * @return The handler to add to a server
     */
    public MuHandler handler() {
        return handler;
    }

    /**
     * @return The operations this handler serves, in an unmodifiable list
     */
    public List<ApiOperation> apiOperations() {
        return apiOperations;
    }

    @Override
    public String toString() {
        return "SpeccedHandler{handler=" + handler + ", apiOperations=" + apiOperations + '}';
    }

    static final class ChainHandler implements MuHandler {
        private final List<MuHandler> handlers;

        ChainHandler(List<MuHandler> handlers) {
            this.handlers = Collections.unmodifiableList(new ArrayList<>(handlers));
        }

        @Override
        public boolean handle(MuRequest request, MuResponse response) throws Exception {
            for (MuHandler handler : handlers) {
                if (handler.handle(request, response)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "ChainHandler" + handlers;
        }
    }
}
