package io.ltr8.tson.schema.meta;

import java.util.Objects;

/**
 * A value held by name, as a host receives it at a slot typed {@code ref<T>} (the kernel's {@code ref_type}): the
 * qualified name as written, {@code myapi.get_orders}, and the value it names once the schema has linked. The read
 * creates it with the name alone; the linker resolves the name, judges the referent a value of {@code T}, and
 * delivers it here exactly once. Before that {@link #get} throws, so a host that reads references out of an
 * unlinked body is told rather than handed {@code null}.
 *
 * <p>Equality is by name: two references to one address are one reference, delivered or not. A {@code TypeRef}
 * component at the same slot receives the name and nothing else, for a host that wants no delivery.
 *
 * @param <T> the referent's host type, the slot's {@code T} as the host binds it
 */
public final class Ref<T> {

    private final String name;
    private volatile T value;
    private volatile boolean delivered;

    private Ref(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** A reference to {@code name}, undelivered. */
    public static <T> Ref<T> to(String name) {
        return new Ref<>(name);
    }

    /** The qualified name as written. */
    public String name() {
        return name;
    }

    /** Whether the linker has delivered the value this names. */
    public boolean isResolved() {
        return delivered;
    }

    /**
     * The value this names.
     *
     * @throws IllegalStateException before the schema has linked
     */
    public T get() {
        if (!delivered) {
            throw new IllegalStateException("'" + name + "' is not yet resolved: a reference is delivered when its "
                    + "schema links, and this one has not");
        }
        return value;
    }

    /**
     * The linker's delivery, once. A second delivery is an internal invariant broken: one read produces one
     * reference and one link judges it.
     */
    @SuppressWarnings("unchecked")
    public void deliver(Object referent) {
        if (delivered) {
            throw new IllegalStateException("'" + name + "' was already delivered");
        }
        this.value = (T) referent;
        this.delivered = true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Ref<?> other && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
