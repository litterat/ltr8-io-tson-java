package io.ltr8.tson.compiler.tree;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, queryable node in a TSON document tree -- the useful result a schema-driven read (a {@code
 * TsonCompiledSchemaRegistry} tree mode) or a schemaless {@code readTree} hands back. The counterpart to
 * Jackson's {@code JsonNode}, but <b>structure-preserving</b> (TSON distinguishes record from map and array
 * from tuple, which JSON conflates) and <b>annotation-aware</b>. Distinct from the grammar-faithful parse
 * tree ({@code ast.CoreValue}): a node carries typed leaf values and query ergonomics the parse tree
 * deliberately doesn't, and drops lexical detail (token quoting) the parse tree keeps.
 *
 * <p><b>Navigation never throws.</b> {@link #get}/{@link #at} return {@link MissingNode} for an absent
 * field/index, so a deep {@code node.at("/orders/3/total").asBigDecimal()} chain is null-safe. "Missing"
 * (no such node in the tree), "null" (the {@code null} token, {@link NullNode}), and "absent" (the {@code
 * _} sentinel, {@link AbsentNode}) are three distinct kinds.
 *
 * <p>Every node carries its own {@link #typeRef()} (the wire or schema type, e.g. {@code "int32"} or
 * {@code "person"}) and {@link #annotations()}.
 */
public sealed interface TsonNode
        permits RecordNode, MapNode, ArrayNode, TupleNode, AtomNode, NullNode, AbsentNode, MissingNode {

    /** This value's own type-ref (e.g. {@code "int32"}, {@code "uuid"}, {@code "person"}), if the wire or schema gave one. */
    Optional<String> typeRef();

    /** This value's own annotations, in order. */
    List<TsonAnnotation> annotations();

    // --- kind ---

    default boolean isRecord()    { return false; }
    default boolean isMap()       { return false; }
    default boolean isArray()     { return false; }
    default boolean isTuple()     { return false; }
    default boolean isAtom()      { return false; }
    default boolean isNull()      { return false; }
    default boolean isAbsent()    { return false; }
    default boolean isMissing()   { return false; }
    default boolean isContainer() { return isRecord() || isMap() || isArray() || isTuple(); }

    // --- navigation (never throws; MissingNode when not applicable or absent) ---

    /** The field/entry named {@code name} (record/map), or {@link MissingNode}. */
    default TsonNode get(String name) { return MissingNode.instance(); }

    /** The element at {@code index} (array/tuple), or {@link MissingNode}. */
    default TsonNode get(int index) { return MissingNode.instance(); }

    /** The record fields (name → node, insertion order), or an empty map. */
    default Map<String, TsonNode> fields() { return Map.of(); }

    /** The array/tuple elements in order, or an empty list. */
    default List<TsonNode> elements() { return List.of(); }

    /**
     * RFC 6901 JSON Pointer navigation from this node: {@code ""} is this node itself, {@code "/a/b"} steps
     * into fields/indices, and any absent step yields {@link MissingNode} (so the whole chain is null-safe).
     * A token that parses as an integer indexes an array/tuple; anything else names a field/entry.
     *
     * @throws IllegalArgumentException if {@code pointer} is non-empty and doesn't start with {@code '/'}
     */
    default TsonNode at(String pointer) {
        if (pointer.isEmpty()) {
            return this;
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("a non-empty JSON Pointer must start with '/': \"" + pointer + "\"");
        }
        TsonNode current = this;
        int from = 1;
        while (from <= pointer.length()) {
            int slash = pointer.indexOf('/', from);
            int end = slash < 0 ? pointer.length() : slash;
            // RFC 6901 unescape: ~1 -> / first, then ~0 -> ~ (order matters, so ~01 decodes to ~1, not /).
            String token = pointer.substring(from, end).replace("~1", "/").replace("~0", "~");
            current = current.step(token);
            from = end + 1;
        }
        return current;
    }

    /** One pointer step: an integer token indexes an array/tuple, anything else names a field/entry. */
    private TsonNode step(String token) {
        if (isArray() || isTuple()) {
            try {
                return get(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                return MissingNode.instance();
            }
        }
        return get(token);
    }

    // --- value (empty for a non-atom / MissingNode; AtomNode overrides as) ---

    /** This node's value cast to {@code type} if it's an atom holding an instance of it, else empty. */
    default <T> Optional<T> as(Class<T> type) { return Optional.empty(); }

    default Optional<String> asString()         { return as(String.class); }
    default Optional<Boolean> asBoolean()       { return as(Boolean.class); }
    default Optional<Number> asNumber()         { return as(Number.class); }
    default Optional<BigInteger> asBigInteger() { return as(BigInteger.class); }
    default Optional<BigDecimal> asBigDecimal() { return as(BigDecimal.class); }
}
