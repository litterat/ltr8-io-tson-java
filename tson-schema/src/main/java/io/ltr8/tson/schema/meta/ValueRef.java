package io.ltr8.tson.schema.meta;

import java.util.Objects;

/**
 * A value held by name, what the kernel's {@code ref} atom reads to at a slot typed {@code meta.ref<T>} with {@code
 * T} no constructor, in a tree read and a bound one alike: the qualified name as written, {@code myapi.get_orders},
 * and the value it names. In a schema document the read creates it with the name alone, and the linker resolves the
 * name, judges the referent a value of {@code T}, and delivers it here exactly once; in a data document the read
 * judges the name in the governing schema and delivers at once. Before delivery {@link #get} throws, so a host that
 * reads references out of an unlinked body is told rather than handed {@code null}.
 *
 * <p>Equality is by name: two references to one address are one reference, delivered or not. A {@code String}
 * component at the same slot receives the name and nothing else, for a host that wants no delivery.
 *
 * @param <T> the referent's host type, the slot's {@code T} as the host binds it
 */
public final class ValueRef<T> {

    private final String name;
    private volatile T value;
    private volatile boolean delivered;

    private ValueRef(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** A reference to {@code name}, undelivered. */
    public static <T> ValueRef<T> to(String name) {
        return new ValueRef<>(name);
    }

    /** The qualified name as written. */
    public String name() {
        return name;
    }

    /** Whether the value this names has been delivered. */
    public boolean isResolved() {
        return delivered;
    }

    /**
     * The value this names.
     *
     * @throws IllegalStateException before the value is delivered
     */
    public T get() {
        if (!delivered) {
            throw new IllegalStateException("'" + name + "' is not yet resolved: a reference is delivered when its "
                    + "schema links, and this one has not");
        }
        return value;
    }

    /**
     * The delivery, once: the linker's for a name a schema document wrote, the read's for one in a data document. A
     * second delivery is an internal invariant broken: one read produces one reference and one judgement delivers it.
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
        return o instanceof ValueRef<?> other && name.equals(other.name);
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
