package io.github.jaylu.muspecced;

import io.muserver.Method;
import io.muserver.openapi.OperationObject;
import io.muserver.openapi.ParameterObject;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One documented OpenAPI operation: an HTTP method and path, plus mu-server's {@link OperationObject}.
 * <p>An {@link OperationObject} on its own has no path or method, because OpenAPI uses those as the keys around
 * it. This class keeps them together so a {@link SpeccedHandler} can say exactly what it serves.</p>
 * <p>Instances are immutable.</p>
 */
public final class ApiOperation {

    private final Method method;
    private final String path;
    private final String shape;
    private final OperationObject operation;

    private ApiOperation(Method method, String path, String shape, OperationObject operation) {
        this.method = method;
        this.path = path;
        this.shape = shape;
        this.operation = operation;
    }

    /**
     * Creates an operation from an {@link OperationObject} built with mu-server's builders.
     * <p>Prefer {@link OpenApiRoutes#operation(Method, String, java.util.function.Consumer)}, which adds path
     * parameters for you. This method checks that every parameter in the path is declared as a path parameter in
     * the operation, and the other way round, as OpenAPI requires.</p>
     *
     * @param method    The HTTP method. <code>CONNECT</code> cannot be documented in OpenAPI.
     * @param path      The path, either as an OpenAPI path such as <code>/users/{id}</code> or as a mu-server URI
     *                  template such as <code>/users/{id : [0-9]+}</code>. Regexes are removed.
     * @param operation The operation
     * @return A new operation
     * @throws IllegalArgumentException if the path and the operation's path parameters do not match
     */
    public static ApiOperation apiOperation(Method method, String path, OperationObject operation) {
        checkMethod(method);
        Objects.requireNonNull(operation, "operation");
        PathTemplate template = PathTemplate.parse("", path);

        Set<String> declared = new TreeSet<>();
        if (operation.parameters() != null) {
            for (ParameterObject p : operation.parameters()) {
                if ("path".equals(p.in())) {
                    declared.add(p.name());
                }
            }
        }
        Set<String> inPath = new TreeSet<>(template.params.keySet());
        if (!declared.equals(inPath)) {
            throw new IllegalArgumentException("The path parameters of " + method + " " + template.openApiPath
                + " are " + inPath + " but the operation declares path parameters " + declared);
        }
        return new ApiOperation(method, template.openApiPath, template.shape, operation);
    }

    static ApiOperation trusted(Method method, PathTemplate template, OperationObject operation) {
        return new ApiOperation(method, template.openApiPath, template.shape, operation);
    }

    static void checkMethod(Method method) {
        if (method == null) {
            throw new IllegalArgumentException("method cannot be null for a documented operation");
        }
        if (method == Method.CONNECT) {
            throw new IllegalArgumentException("OpenAPI cannot document CONNECT operations");
        }
    }

    /**
     * @return The HTTP method
     */
    public Method method() {
        return method;
    }

    /**
     * @return The OpenAPI path, e.g. <code>/users/{id}</code>
     */
    public String path() {
        return path;
    }

    /**
     * @return The operation
     */
    public OperationObject operation() {
        return operation;
    }

    /**
     * @param prefix A path prefix, e.g. <code>/v1</code>
     * @return A copy of this operation with the prefix added to its path
     */
    public ApiOperation withPathPrefix(String prefix) {
        return new ApiOperation(method, PathTemplate.join(prefix, path), PathTemplate.join(prefix, shape), operation);
    }

    /** The path with parameter names removed, e.g. <code>/users/{}</code>, used to detect clashing operations */
    String shape() {
        return shape;
    }

    @Override
    public String toString() {
        return method + " " + path;
    }
}
