package tech.jaylu.muspecced;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Captures a generic type so that it can be documented, for example <code>new TypeRef&lt;List&lt;User&gt;&gt;() {}</code>.
 * <p>A TypeRef can be passed anywhere a {@link Type} is accepted, such as
 * {@link OperationDoc#response(int, Type, String)}.</p>
 *
 * @param <T> The type to capture
 */
public abstract class TypeRef<T> implements Type {
    private final Type type;

    protected TypeRef() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType)) {
            throw new IllegalStateException("TypeRef must be created with a type argument, e.g. new TypeRef<List<User>>() {}");
        }
        this.type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
    }

    /**
     * @return The captured type
     */
    public Type type() {
        return type;
    }

    @Override
    public String getTypeName() {
        return type.getTypeName();
    }

    @Override
    public String toString() {
        return "TypeRef<" + type.getTypeName() + ">";
    }

    static Type unwrap(Type type) {
        return type instanceof TypeRef ? ((TypeRef<?>) type).type() : type;
    }
}
