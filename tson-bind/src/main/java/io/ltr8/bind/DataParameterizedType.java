package io.ltr8.bind;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;

/**
 * A hand-built {@link ParameterizedType}, for a caller that needs to resolve a {@link DataClass}
 * for a generically-typed target (e.g. {@code List<String>}, {@code Map<String, Integer>}) but has
 * no real Java field or method signature to reflect one off of. Type erasure means a bare
 * {@link Class} alone (e.g. {@code List.class}) carries no element/key/value type information, and
 * {@link DataBindContext#getDescriptor(Class, Type)} requires a genuine {@code ParameterizedType}
 * to recover it for a {@code Collection}/{@code Map} target -- this supplies one directly, without
 * needing an existing generically-typed declaration anywhere.
 *
 * <p>Always top-level ({@link #getOwnerType()} is {@code null}); construct nested generic types
 * (e.g. {@code List<Map<String, Integer>>}) by passing another {@code DataParameterizedType} as one
 * of {@code actualTypeArguments}.
 */
public final class DataParameterizedType implements ParameterizedType {

    private final Type rawType;
    private final Type[] actualTypeArguments;

    public DataParameterizedType(Type rawType, Type... actualTypeArguments) {
        this.rawType = Objects.requireNonNull(rawType, "raw type cannot be null");
        this.actualTypeArguments = Objects.requireNonNull(actualTypeArguments, "type arguments cannot be null");
    }

    @Override
    public Type[] getActualTypeArguments() {
        return actualTypeArguments.clone();
    }

    @Override
    public Type getRawType() {
        return rawType;
    }

    @Override
    public Type getOwnerType() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ParameterizedType that)) {
            return false;
        }
        return Objects.equals(rawType, that.getRawType())
                && that.getOwnerType() == null
                && Arrays.equals(actualTypeArguments, that.getActualTypeArguments());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(actualTypeArguments) ^ Objects.hashCode(rawType);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(((Class<?>) rawType).getName());
        if (actualTypeArguments.length > 0) {
            sb.append('<');
            for (int i = 0; i < actualTypeArguments.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(actualTypeArguments[i].getTypeName());
            }
            sb.append('>');
        }
        return sb.toString();
    }
}
