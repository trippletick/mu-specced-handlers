package tech.jaylu.muspecced;

import io.muserver.openapi.SchemaObject;
import io.muserver.openapi.SchemaObjectBuilder;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;

/**
 * A {@link SchemaProvider} that describes types using reflection, with no extra dependencies.
 * <ul>
 *     <li>Strings, numbers, booleans, dates, UUIDs, URIs and binary types map to the matching JSON type and format.</li>
 *     <li>Enums become strings with an <code>enum</code> list of the constant names.</li>
 *     <li>Arrays and collections become arrays; sets have <code>uniqueItems</code>.</li>
 *     <li><code>Map&lt;String, V&gt;</code> becomes an object with <code>additionalProperties</code>.</li>
 *     <li><code>Optional&lt;T&gt;</code> is described as <code>T</code>.</li>
 *     <li>Records are described by their components. Other classes are described by their public fields and
 *     public getters, including inherited ones. Type variables such as <code>Page&lt;User&gt;</code> are resolved.</li>
 *     <li>Classes in <code>java.*</code> that are not listed above are described as a plain object.</li>
 * </ul>
 * <p>Schemas are inlined because mu-server's OpenAPI model has no <code>$ref</code> support. A type that refers to
 * itself is described as a plain object at the point of recursion.</p>
 * <p>Use {@link OpenApiRoutes#withSchema(Class, SchemaObject)} to replace the schema of a particular class.</p>
 */
public class ReflectionSchemaProvider implements SchemaProvider {

    private static final Method IS_RECORD = findMethod(Class.class, "isRecord");
    private static final Method GET_RECORD_COMPONENTS = findMethod(Class.class, "getRecordComponents");

    private final Map<Class<?>, SchemaObject> overrides;

    /**
     * Creates a provider with no schema overrides
     */
    public ReflectionSchemaProvider() {
        this(new ConcurrentHashMap<>());
    }

    /**
     * @param overrides Schemas to use for specific classes instead of reflection. The map is read each time a
     *                  schema is generated, so it may be added to later.
     */
    public ReflectionSchemaProvider(Map<Class<?>, SchemaObject> overrides) {
        this.overrides = Objects.requireNonNull(overrides, "overrides");
    }

    @Override
    public SchemaObject schemaFor(Type type) {
        return schemaFor(type, new HashSet<>());
    }

    private SchemaObject schemaFor(Type type, Set<Class<?>> visiting) {
        type = TypeRef.unwrap(type);
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            type = upper.length == 0 ? Object.class : upper[0];
        }
        if (type instanceof TypeVariable) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            type = bounds.length == 0 ? Object.class : bounds[0];
        }
        if (type instanceof GenericArrayType) {
            return arrayOf(schemaFor(((GenericArrayType) type).getGenericComponentType(), visiting), false);
        }

        Class<?> raw = rawClass(type);
        SchemaObject override = overrides.get(raw);
        if (override != null) {
            return override;
        }

        SchemaObjectBuilder simple = simpleType(raw);
        if (simple != null) {
            return simple.build();
        }
        if (raw.isEnum()) {
            // mu-server writes enum values using Enum.name(), so the constants themselves must be given
            return schemaObject().withType("string").withEnumValue(Arrays.asList((Object[]) raw.getEnumConstants())).build();
        }
        if (raw.isArray()) {
            return arrayOf(schemaFor(raw.getComponentType(), visiting), false);
        }
        if (Optional.class.equals(raw)) {
            return schemaFor(typeArgument(type, 0), visiting);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return arrayOf(schemaFor(typeArgument(type, 0), visiting), Set.class.isAssignableFrom(raw));
        }
        if (Map.class.isAssignableFrom(raw)) {
            Type valueType = typeArgument(type, 1);
            SchemaObjectBuilder map = schemaObject().withType("object");
            if (!Object.class.equals(valueType)) {
                map.withAdditionalProperties(schemaFor(valueType, visiting));
            }
            return map.build();
        }
        if (raw.isPrimitive() || raw.getName().startsWith("java.") || raw.getName().startsWith("javax.")) {
            return schemaObject().withType("object").build();
        }
        if (visiting.contains(raw)) {
            return schemaObject().withType("object").withTitle(raw.getSimpleName())
                .withDescription("A nested " + raw.getSimpleName()).build();
        }

        visiting.add(raw);
        try {
            Map<TypeVariable<?>, Type> typeArgs = typeArguments(type, raw);
            Map<String, SchemaObject> properties = new LinkedHashMap<>();
            for (Map.Entry<String, Type> property : properties(raw).entrySet()) {
                properties.put(property.getKey(), schemaFor(resolve(property.getValue(), typeArgs), visiting));
            }
            return schemaObject()
                .withType("object")
                .withTitle(raw.getSimpleName())
                .withProperties(properties.isEmpty() ? null : properties)
                .build();
        } finally {
            visiting.remove(raw);
        }
    }

    private static SchemaObject arrayOf(SchemaObject items, boolean unique) {
        return schemaObject().withType("array").withItems(items).withUniqueItems(unique ? true : null).build();
    }

    private static SchemaObjectBuilder simpleType(Class<?> type) {
        if (CharSequence.class.isAssignableFrom(type) || type.equals(char.class) || type.equals(Character.class)) {
            return schemaObject().withType("string");
        } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            return schemaObject().withType("boolean");
        } else if (type.equals(int.class) || type.equals(Integer.class) || type.equals(short.class) || type.equals(Short.class)
            || type.equals(byte.class) || type.equals(Byte.class) || type.equals(OptionalInt.class)) {
            return schemaObject().withType("integer").withFormat("int32");
        } else if (type.equals(long.class) || type.equals(Long.class) || type.equals(OptionalLong.class)) {
            return schemaObject().withType("integer").withFormat("int64");
        } else if (type.equals(BigInteger.class)) {
            return schemaObject().withType("integer");
        } else if (type.equals(float.class) || type.equals(Float.class)) {
            return schemaObject().withType("number").withFormat("float");
        } else if (type.equals(double.class) || type.equals(Double.class) || type.equals(OptionalDouble.class)) {
            return schemaObject().withType("number").withFormat("double");
        } else if (type.equals(BigDecimal.class) || type.equals(Number.class)) {
            return schemaObject().withType("number");
        } else if (Date.class.isAssignableFrom(type) || type.equals(Instant.class) || type.equals(OffsetDateTime.class)
            || type.equals(ZonedDateTime.class) || type.equals(LocalDateTime.class)) {
            return schemaObject().withType("string").withFormat("date-time");
        } else if (type.equals(LocalDate.class)) {
            return schemaObject().withType("string").withFormat("date");
        } else if (type.equals(UUID.class)) {
            return schemaObject().withType("string").withFormat("uuid");
        } else if (type.equals(URI.class) || type.equals(URL.class)) {
            return schemaObject().withType("string").withFormat("uri");
        } else if (type.equals(byte[].class) || InputStream.class.isAssignableFrom(type) || File.class.isAssignableFrom(type)) {
            return schemaObject().withType("string").withFormat("binary");
        } else if (java.time.temporal.Temporal.class.isAssignableFrom(type)) {
            return schemaObject().withType("string");
        }
        return null;
    }

    /**
     * @return The property names of the class mapped to their generic types, in declaration order
     */
    private static Map<String, Type> properties(Class<?> raw) {
        Map<String, Type> props = new LinkedHashMap<>();
        if (isRecord(raw)) {
            try {
                Object[] components = (Object[]) GET_RECORD_COMPONENTS.invoke(raw);
                for (Object component : components) {
                    Method getName = component.getClass().getMethod("getName");
                    Method getGenericType = component.getClass().getMethod("getGenericType");
                    props.put((String) getName.invoke(component), (Type) getGenericType.invoke(component));
                }
                return props;
            } catch (Exception e) {
                throw new IllegalStateException("Could not read record components of " + raw.getName(), e);
            }
        }

        Map<String, Method> getters = new LinkedHashMap<>();
        for (Method method : raw.getMethods()) {
            String name = getterPropertyName(method);
            if (name != null) {
                getters.put(name, method);
            }
        }

        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = raw; c != null && !Object.class.equals(c); c = c.getSuperclass()) {
            hierarchy.add(c);
        }
        Collections.reverse(hierarchy);
        for (Class<?> c : hierarchy) {
            for (Field field : c.getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) {
                    continue;
                }
                Method getter = getters.remove(field.getName());
                if (getter != null) {
                    props.put(field.getName(), getter.getGenericReturnType());
                } else if (Modifier.isPublic(mod)) {
                    props.put(field.getName(), field.getGenericType());
                }
            }
        }
        // Getters with no backing field, e.g. computed values
        List<String> remaining = new ArrayList<>(getters.keySet());
        Collections.sort(remaining);
        for (String name : remaining) {
            props.put(name, getters.get(name).getGenericReturnType());
        }
        return props;
    }

    private static String getterPropertyName(Method method) {
        if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
            || method.getDeclaringClass().equals(Object.class) || method.isBridge() || method.isSynthetic()) {
            return null;
        }
        String name = method.getName();
        String suffix;
        if (name.startsWith("get") && name.length() > 3 && !void.class.equals(method.getReturnType())) {
            suffix = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2 && (method.getReturnType().equals(boolean.class) || method.getReturnType().equals(Boolean.class))) {
            suffix = name.substring(2);
        } else {
            return null;
        }
        if (suffix.length() > 1 && Character.isUpperCase(suffix.charAt(1))) {
            return suffix; // e.g. getURL -> URL, as java.beans does
        }
        return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private static boolean isRecord(Class<?> type) {
        if (IS_RECORD == null) {
            return false;
        }
        try {
            return (Boolean) IS_RECORD.invoke(type);
        } catch (Exception e) {
            return false;
        }
    }

    private static Method findMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static Class<?> rawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return Object.class;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (args.length > index) {
                return args[index];
            }
        }
        return Object.class;
    }

    private static Map<TypeVariable<?>, Type> typeArguments(Type type, Class<?> raw) {
        Map<TypeVariable<?>, Type> map = new HashMap<>();
        if (type instanceof ParameterizedType) {
            TypeVariable<?>[] vars = raw.getTypeParameters();
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            for (int i = 0; i < vars.length && i < args.length; i++) {
                map.put(vars[i], args[i]);
            }
        }
        return map;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> typeArgs) {
        if (typeArgs.isEmpty()) {
            return type;
        }
        if (type instanceof TypeVariable) {
            Type resolved = typeArgs.get(type);
            return resolved == null ? type : resolved;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type[] args = pt.getActualTypeArguments().clone();
            for (int i = 0; i < args.length; i++) {
                args[i] = resolve(args[i], typeArgs);
            }
            return new ResolvedParameterizedType(pt.getRawType(), args, pt.getOwnerType());
        }
        return type;
    }

    private static final class ResolvedParameterizedType implements ParameterizedType {
        private final Type rawType;
        private final Type[] args;
        private final Type ownerType;

        ResolvedParameterizedType(Type rawType, Type[] args, Type ownerType) {
            this.rawType = rawType;
            this.args = args;
            this.ownerType = ownerType;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public String toString() {
            return rawType.getTypeName() + Arrays.toString(args);
        }
    }
}
