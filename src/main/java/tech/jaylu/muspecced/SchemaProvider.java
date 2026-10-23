package tech.jaylu.muspecced;

import io.muserver.openapi.SchemaObject;

import java.lang.reflect.Type;

/**
 * Converts Java types into OpenAPI schemas, for parameters, request bodies and response bodies.
 * <p>The default is {@link ReflectionSchemaProvider}. Implement this to plug in another generator, for example one
 * that reads Jackson or Bean Validation annotations.</p>
 */
public interface SchemaProvider {

    /**
     * @param type The type to describe. This may be a {@link Class}, a {@link java.lang.reflect.ParameterizedType}
     *             or a {@link TypeRef}.
     * @return The schema for the type
     */
    SchemaObject schemaFor(Type type);

}
