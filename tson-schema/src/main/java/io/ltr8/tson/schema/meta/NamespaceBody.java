package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code namespace} constructor's own vocabulary, resolved ([TSON-SCHEMA] §4.1, §8.1): a map type
 * keyed by names. A namespace type is an instance, as a map type is one of {@code map} -- the kernel's {@code
 * schema} and {@code field_map}, and a schema's own {@code api => !namespace { key_type: identifier  value_type:
 * method }} -- and a value of it is the map its facets describe, delivered as a {@link Namespace} where the position
 * asks for one. {@code access_pattern}/{@code size_type} are fixed ({@code NAMED}/{@code VARIABLE}) and never appear in
 * output.
 *
 * <p><b>{@code key_type} names the family every key is read and judged under</b>, an identifier family. Absent, the
 * host of the value supplies it: a record's {@code fields} are keyed by the record's {@code name_type}, so {@code
 * field_map} states none, and a key is read by its spelling ({@link #read}) and judged under the host's family once
 * that is resolved.
 *
 * <p>A value is never void and has no size bounds; {@code ordered} says whether key order is part of it, as a map's
 * does, and output keeps the order written either way ([TSON-SCHEMA] §7.5).
 *
 * @param keyType   the identifier family the keys are names of; empty where the host supplies it
 * @param valueType the type of every value
 * @param ordered   whether key order is part of a value
 */
@Typename(name = "namespace")
public record NamespaceBody(TypeChain chain, @Field("key_type") Optional<TypeRef> keyType,
                            @Field("value_type") TypeRef valueType, boolean ordered) implements Product {

    /** The kernel's constructor whose instances are namespace types. */
    public static final String NAMESPACE = "namespace";

    /** What a key of a host-supplied family is read as: a token, its spelling, judged later by the host. */
    public static final String HOST_KEYS = "text";

    /** The chain normalised: absent and empty are one list. */
    @Record
    public NamespaceBody {
        chain = TypeChain.orEmpty(chain);
        keyType = keyType == null ? Optional.empty() : keyType;
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public NamespaceBody(Optional<TypeRef> keyType, TypeRef valueType, boolean ordered) {
        this(TypeChain.EMPTY, keyType, valueType, ordered);
    }

    @Override
    public NamespaceBody withChain(TypeChain chain) {
        return new NamespaceBody(chain, keyType, valueType, ordered);
    }

    /** Whether the key family is stated here rather than supplied by the host of a value. */
    public boolean keysPinned() {
        return keyType.isPresent();
    }

    /**
     * The map a value is read by: keys of the stated family, or read as {@value #HOST_KEYS}, whose reader takes any
     * spelling, where the host supplies the family; values of {@code value_type}, never void; unbounded.
     */
    public MapBody read() {
        return new MapBody(chain, keyType.orElse(TypeRef.of(HOST_KEYS)), valueType, false, ordered,
                Optional.empty(), Optional.empty());
    }
}
