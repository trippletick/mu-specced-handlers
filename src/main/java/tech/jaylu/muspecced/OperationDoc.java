package tech.jaylu.muspecced;

import io.muserver.Method;
import io.muserver.openapi.HeaderObject;
import io.muserver.openapi.MediaTypeObject;
import io.muserver.openapi.OperationObject;
import io.muserver.openapi.OperationObjectBuilder;
import io.muserver.openapi.ParameterObject;
import io.muserver.openapi.ResponseObject;
import io.muserver.openapi.SchemaObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static io.muserver.openapi.HeaderObjectBuilder.headerObject;
import static io.muserver.openapi.MediaTypeObjectBuilder.mediaTypeObject;
import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.RequestBodyObjectBuilder.requestBodyObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;

/**
 * Describes a single OpenAPI operation. An instance is passed to the documentation callback of
 * {@link OpenApiRoutes#route(Method, String, Consumer, io.muserver.RouteHandler)} and
 * {@link OpenApiRoutes#operation(Method, String, Consumer)}.
 * <p>Body and parameter types are given as a {@link Type}: pass a class such as <code>User.class</code>, or a
 * {@link TypeRef} for generic types, such as <code>new TypeRef&lt;List&lt;User&gt;&gt;() {}</code>.</p>
 * <p>Examples are written as JSON by mu-server, which supports strings, numbers, booleans, lists and maps. Other
 * objects are written using their <code>toString()</code> value.</p>
 */
public final class OperationDoc {

    /** The media type used when none is given */
    public static final String JSON = "application/json";

    private final Method method;
    private final PathTemplate path;

    private String summary;
    private String description;
    private String operationId;
    private final List<String> tags = new ArrayList<>();
    private boolean deprecated;
    private final List<ParamDef> params = new ArrayList<>();
    private final List<ParameterObject> rawParams = new ArrayList<>();
    private String requestBodyDescription;
    private Boolean requestBodyRequired;
    private final Map<String, MediaDef> requestBodyContent = new LinkedHashMap<>();
    private final Map<String, ResponseDef> responses = new LinkedHashMap<>();
    private final List<Consumer<OperationObjectBuilder>> customizers = new ArrayList<>();

    OperationDoc(Method method, PathTemplate path) {
        this.method = method;
        this.path = path;
    }

    /**
     * @param summary A short summary of what the operation does
     * @return This doc
     */
    public OperationDoc summary(String summary) {
        this.summary = summary;
        return this;
    }

    /**
     * @param description A longer description of the operation. CommonMark syntax may be used.
     * @return This doc
     */
    public OperationDoc description(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param operationId A unique ID for the operation. When not set, {@link OpenApiDocs} generates one from the
     *                    method and the final path, including any context prefix, e.g. <code>GET_v1_users__id_</code>
     * @return This doc
     */
    public OperationDoc operationId(String operationId) {
        this.operationId = operationId;
        return this;
    }

    /**
     * @param tags Tags used to group operations. Use {@link OpenApiDocs#withTag(String, String)} to describe a tag.
     * @return This doc
     */
    public OperationDoc tag(String... tags) {
        this.tags.addAll(Arrays.asList(tags));
        return this;
    }

    /**
     * Marks the operation as deprecated
     *
     * @return This doc
     */
    public OperationDoc deprecated() {
        this.deprecated = true;
        return this;
    }

    /**
     * Documents a path parameter. The name must appear in the route's URI template.
     * <p>Path parameters that are not documented are added automatically as strings. When the template has a regex,
     * such as <code>{id : [0-9]+}</code>, and the type is a string, the regex is added as the schema pattern.</p>
     *
     * @param name        The parameter name, as in the URI template
     * @param type        The type of the value, e.g. <code>long.class</code>
     * @param description A description of the parameter
     * @return This doc
     */
    public OperationDoc pathParam(String name, Type type, String description) {
        return addParam(new ParamDef("path", name, type, description, true, null));
    }

    /**
     * Documents an optional querystring parameter
     *
     * @param name        The parameter name
     * @param type        The type of the value. Use a collection type, e.g. <code>new TypeRef&lt;List&lt;String&gt;&gt;() {}</code>,
     *                    for a parameter that can be repeated.
     * @param description A description of the parameter
     * @return This doc
     */
    public OperationDoc queryParam(String name, Type type, String description) {
        return addParam(new ParamDef("query", name, type, description, false, null));
    }

    /**
     * Documents an optional querystring parameter with a default value
     *
     * @param name         The parameter name
     * @param type         The type of the value
     * @param description  A description of the parameter
     * @param defaultValue The value used by the handler when the parameter is not given
     * @return This doc
     */
    public OperationDoc queryParam(String name, Type type, String description, Object defaultValue) {
        return addParam(new ParamDef("query", name, type, description, false, defaultValue));
    }

    /**
     * Documents a required querystring parameter
     *
     * @param name        The parameter name
     * @param type        The type of the value
     * @param description A description of the parameter
     * @return This doc
     */
    public OperationDoc requiredQueryParam(String name, Type type, String description) {
        return addParam(new ParamDef("query", name, type, description, true, null));
    }

    /**
     * Documents an optional request header
     *
     * @param name        The header name
     * @param type        The type of the value
     * @param description A description of the header
     * @return This doc
     */
    public OperationDoc headerParam(String name, Type type, String description) {
        return addParam(new ParamDef("header", name, type, description, false, null));
    }

    /**
     * Documents a required request header
     *
     * @param name        The header name
     * @param type        The type of the value
     * @param description A description of the header
     * @return This doc
     */
    public OperationDoc requiredHeaderParam(String name, Type type, String description) {
        return addParam(new ParamDef("header", name, type, description, true, null));
    }

    /**
     * Documents an optional cookie
     *
     * @param name        The cookie name
     * @param type        The type of the value
     * @param description A description of the cookie
     * @return This doc
     */
    public OperationDoc cookieParam(String name, Type type, String description) {
        return addParam(new ParamDef("cookie", name, type, description, false, null));
    }

    /**
     * Adds a parameter built with {@link io.muserver.openapi.ParameterObjectBuilder}, for cases the other methods
     * do not cover.
     *
     * @param parameter The parameter
     * @return This doc
     */
    public OperationDoc parameter(ParameterObject parameter) {
        rawParams.add(Objects.requireNonNull(parameter, "parameter"));
        return this;
    }

    /**
     * Documents a required JSON request body
     *
     * @param type        The type the handler reads the body as
     * @param description A description of the body
     * @return This doc
     */
    public OperationDoc requestBody(Type type, String description) {
        return requestBody(JSON, type, description, null);
    }

    /**
     * Documents a required request body. Call this more than once to document several media types.
     *
     * @param mediaType   The media type, e.g. <code>application/json</code> or <code>text/plain</code>
     * @param type        The type the handler reads the body as
     * @param description A description of the body
     * @return This doc
     */
    public OperationDoc requestBody(String mediaType, Type type, String description) {
        return requestBody(mediaType, type, description, null);
    }

    /**
     * Documents a required request body with an example
     *
     * @param mediaType   The media type
     * @param type        The type the handler reads the body as
     * @param description A description of the body
     * @param example     An example body, e.g. a <code>Map</code> for a JSON object
     * @return This doc
     */
    public OperationDoc requestBody(String mediaType, Type type, String description, Object example) {
        requestBodyContent.put(requireMediaType(mediaType), new MediaDef(Objects.requireNonNull(type, "type"), example));
        if (description != null) {
            requestBodyDescription = description;
        }
        if (requestBodyRequired == null) {
            requestBodyRequired = true;
        }
        return this;
    }

    /**
     * Marks the request body as optional. By default a documented request body is required.
     *
     * @return This doc
     */
    public OperationDoc requestBodyOptional() {
        requestBodyRequired = false;
        return this;
    }

    /**
     * Documents a response with no body
     *
     * @param status      The HTTP status code
     * @param description A description of the response
     * @return This doc
     */
    public OperationDoc response(int status, String description) {
        responseDef(status).description = Objects.requireNonNull(description, "description");
        return this;
    }

    /**
     * Documents a response with a JSON body
     *
     * @param status      The HTTP status code
     * @param type        The type the handler writes as the body
     * @param description A description of the response
     * @return This doc
     */
    public OperationDoc response(int status, Type type, String description) {
        return response(status, JSON, type, description, null);
    }

    /**
     * Documents a response body. Call this more than once with the same status to document several media types.
     *
     * @param status      The HTTP status code
     * @param mediaType   The media type, e.g. <code>application/json</code> or <code>text/plain</code>
     * @param type        The type the handler writes as the body
     * @param description A description of the response
     * @return This doc
     */
    public OperationDoc response(int status, String mediaType, Type type, String description) {
        return response(status, mediaType, type, description, null);
    }

    /**
     * Documents a response body with an example
     *
     * @param status      The HTTP status code
     * @param mediaType   The media type
     * @param type        The type the handler writes as the body
     * @param description A description of the response
     * @param example     An example body, e.g. a <code>Map</code> for a JSON object
     * @return This doc
     */
    public OperationDoc response(int status, String mediaType, Type type, String description, Object example) {
        ResponseDef def = responseDef(status);
        def.description = Objects.requireNonNull(description, "description");
        def.content.put(requireMediaType(mediaType), new MediaDef(Objects.requireNonNull(type, "type"), example));
        return this;
    }

    /**
     * Documents a header sent with a response, e.g. <code>Location</code> on a <code>201</code>.
     * The response itself must also be documented.
     *
     * @param status      The HTTP status code of the response
     * @param name        The header name
     * @param type        The type of the value
     * @param description A description of the header
     * @return This doc
     */
    public OperationDoc responseHeader(int status, String name, Type type, String description) {
        responseDef(status).headers.put(Objects.requireNonNull(name, "name"), new ParamDef("header", name, type, description, false, null));
        return this;
    }

    /**
     * Changes the generated operation directly, for anything this class does not cover, such as security or
     * external docs. Customizers run after everything else is set.
     *
     * @param customizer A function that modifies the operation builder
     * @return This doc
     */
    public OperationDoc customize(Consumer<OperationObjectBuilder> customizer) {
        customizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    String summaryValue() {
        return summary;
    }

    boolean hasResponses() {
        return !responses.isEmpty();
    }

    private OperationDoc addParam(ParamDef def) {
        Objects.requireNonNull(def.name, "name");
        Objects.requireNonNull(def.type, "type");
        params.add(def);
        return this;
    }

    private ResponseDef responseDef(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Invalid HTTP status code " + status);
        }
        return responses.computeIfAbsent(String.valueOf(status), k -> new ResponseDef());
    }

    private static String requireMediaType(String mediaType) {
        if (mediaType == null || mediaType.trim().isEmpty()) {
            throw new IllegalArgumentException("mediaType cannot be empty");
        }
        return mediaType;
    }

    /**
     * Checks the doc against the route and builds the operation
     */
    OperationObject build(SchemaProvider schemas) {
        String where = method + " " + path.openApiPath;

        for (ParamDef def : params) {
            if (def.in.equals("path") && !path.params.containsKey(def.name)) {
                throw new IllegalArgumentException("Path parameter '" + def.name + "' is documented for " + where
                    + " but the URI template has no such parameter. Template parameters: " + path.params.keySet());
            }
        }

        List<ParameterObject> parameters = new ArrayList<>();
        // Path parameters first, in template order, so undocumented ones can be added automatically
        for (Map.Entry<String, String> templateParam : path.params.entrySet()) {
            ParamDef def = null;
            for (ParamDef p : params) {
                if (p.in.equals("path") && p.name.equals(templateParam.getKey())) {
                    def = p;
                }
            }
            if (def == null) {
                def = new ParamDef("path", templateParam.getKey(), String.class, null, true, null);
            }
            SchemaObject schema = schemas.schemaFor(def.type);
            String regex = templateParam.getValue();
            if (regex != null && "string".equals(schema.type())) {
                schema = schema.toBuilder().withPattern(Pattern.compile(regex)).build();
            }
            parameters.add(parameterObject()
                .withName(def.name)
                .withIn("path")
                .withRequired(true)
                .withDescription(def.description)
                .withSchema(schema)
                .build());
        }
        for (ParamDef def : params) {
            if (!def.in.equals("path")) {
                parameters.add(def.toParameter(schemas));
            }
        }
        parameters.addAll(rawParams);
        for (int i = 0; i < parameters.size(); i++) {
            for (int j = i + 1; j < parameters.size(); j++) {
                ParameterObject a = parameters.get(i);
                ParameterObject b = parameters.get(j);
                if (a.name().equals(b.name()) && a.in().equals(b.in())) {
                    throw new IllegalArgumentException("The " + a.in() + " parameter '" + a.name() + "' is documented more than once for " + where);
                }
            }
        }

        Map<String, ResponseObject> responseObjects = new LinkedHashMap<>();
        for (Map.Entry<String, ResponseDef> entry : responses.entrySet()) {
            ResponseDef def = entry.getValue();
            if (def.description == null) {
                throw new IllegalArgumentException("Response " + entry.getKey() + " for " + where
                    + " has a header but was not documented. Call response(" + entry.getKey() + ", ...) as well.");
            }
            Map<String, HeaderObject> headers = new LinkedHashMap<>();
            for (ParamDef header : def.headers.values()) {
                headers.put(header.name, headerObject()
                    .withDescription(header.description)
                    .withSchema(schemas.schemaFor(header.type))
                    .build());
            }
            responseObjects.put(entry.getKey(), responseObject()
                .withDescription(def.description)
                .withContent(content(def.content, schemas))
                .withHeaders(headers.isEmpty() ? null : headers)
                .build());
        }
        if (responseObjects.isEmpty()) {
            responseObjects.put("200", responseObject().withDescription("OK").build());
        }

        OperationObjectBuilder builder = operationObject()
            .withSummary(summary)
            .withDescription(description)
            .withOperationId(operationId)
            .withTags(tags.isEmpty() ? null : new ArrayList<>(tags))
            .withDeprecated(deprecated ? true : null)
            .withParameters(parameters)
            .withRequestBody(requestBodyContent.isEmpty() ? null : requestBodyObject()
                .withDescription(requestBodyDescription)
                .withRequired(requestBodyRequired)
                .withContent(content(requestBodyContent, schemas))
                .build())
            .withResponses(responsesObject().withHttpStatusCodes(responseObjects).build());
        for (Consumer<OperationObjectBuilder> customizer : customizers) {
            customizer.accept(builder);
        }
        return builder.build();
    }

    private static Map<String, MediaTypeObject> content(Map<String, MediaDef> defs, SchemaProvider schemas) {
        if (defs.isEmpty()) {
            return null;
        }
        Map<String, MediaTypeObject> content = new LinkedHashMap<>();
        for (Map.Entry<String, MediaDef> entry : defs.entrySet()) {
            content.put(entry.getKey(), mediaTypeObject()
                .withSchema(schemas.schemaFor(entry.getValue().type))
                .withExample(entry.getValue().example)
                .build());
        }
        return content;
    }

    private static final class ParamDef {
        final String in;
        final String name;
        final Type type;
        final String description;
        final boolean required;
        final Object defaultValue;

        ParamDef(String in, String name, Type type, String description, boolean required, Object defaultValue) {
            this.in = in;
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
            this.defaultValue = defaultValue;
        }

        ParameterObject toParameter(SchemaProvider schemas) {
            SchemaObject schema = schemas.schemaFor(type);
            if (defaultValue != null) {
                schema = schema.toBuilder().withDefaultValue(defaultValue).build();
            }
            return parameterObject()
                .withName(name)
                .withIn(in)
                .withRequired(required ? true : null)
                .withDescription(description)
                .withSchema(schema)
                .build();
        }
    }

    private static final class MediaDef {
        final Type type;
        final Object example;

        MediaDef(Type type, Object example) {
            this.type = type;
            this.example = example;
        }
    }

    private static final class ResponseDef {
        String description;
        final Map<String, MediaDef> content = new LinkedHashMap<>();
        final Map<String, ParamDef> headers = new LinkedHashMap<>();
    }
}
