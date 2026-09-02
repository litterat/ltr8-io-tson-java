package io.ltr8.tson.tree;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * An immutable, queryable node in a TSON document tree -- the useful result a schema-driven read (a {@code
 * TsonCompiledSchemaRegistry} tree mode) or a schemaless {@code readTree} hands back. The counterpart to
 * Jackson's {@code JsonNode}, but <b>structure-preserving</b> (TSON distinguishes record from map and array
 * from tuple, which JSON conflates) and <b>annotation-aware</b>. Distinct from the grammar-faithful parse
 * tree ({@code ast.CoreValue}): a node carries typed leaf values and query ergonomics the parse tree
 * deliberately doesn't, and drops lexical detail (token quoting) the parse tree keeps.
 *
 * <p><b>Navigation never throws.</b> {@link #get}/{@link #at} return {@link TsonMissing} for an absent
 * field/index, so a deep {@code node.at("/orders/3/total").asBigDecimal()} chain is null-safe. "Absent"
 * (a position that was written but holds no value -- the {@code _} sentinel, {@link TsonAbsent}) and
 * "missing" (no such node in the tree at all, {@link TsonMissing})
 * are distinct kinds. A lenient chain still says <em>where</em> it
 * failed: the missing carries the pointer of the step that failed, readable via {@link #missingPath()}.
 *
 * <p>Every node carries its own {@link #typeRef()} (the wire or schema type, e.g. {@code "int32"} or
 * {@code "person"}) and {@link #annotations()}.
 */
public sealed interface TsonValue
        permits TsonRecord, TsonMap, TsonArray, TsonTuple, TsonAtom, TsonAbsent, TsonMissing {

    /** This value's own type-ref (e.g. {@code "int32"}, {@code "uuid"}, {@code "person"}), if the wire or schema gave one. */
    Optional<String> typeRef();

    /** This value's own annotations, in order. */
    List<TsonAnnotation> annotations();

    /**
     * A copy of this node with {@code leading} placed ahead of its own annotations, or this node unchanged
     * when {@code leading} is empty.
     *
     * <p>Exists for the one position a reader cannot annotate a node as it builds it: a value whose type is
     * chosen by a preceding {@code !typeName} (a choice variant, a record subtype). Its annotations sit
     * <em>before</em> that type-ref in the grammar ({@code data-value = *annotation [type-ref] core-value}),
     * so whatever consumed the type-ref to decide which reader to use has necessarily already consumed them
     * — and the reader that ends up building the node never sees them. Attaching afterwards puts them where
     * they belong, on the value they were written against. {@code leading} goes first because it was written
     * first, preserving §3.1's source order.
     *
     * <p>{@link TsonMissing} is a navigation artifact rather than a value, so it has nothing to annotate and
     * returns itself.
     */
    default TsonValue withAnnotations(List<TsonAnnotation> leading) {
        if (leading.isEmpty()) {
            return this;
        }
        List<TsonAnnotation> merged =
                Stream.concat(leading.stream(), annotations().stream()).toList();
        return switch (this) {
            case TsonRecord n -> new TsonRecord(n.fields(), n.typeRef(), merged);
            case TsonMap n -> new TsonMap(n.entries(), n.typeRef(), merged);
            case TsonArray n -> new TsonArray(n.elements(), n.typeRef(), merged);
            case TsonTuple n -> new TsonTuple(n.elements(), n.typeRef(), merged);
            case TsonAtom n -> new TsonAtom(n.value(), n.typeRef(), merged);
            case TsonAbsent n -> new TsonAbsent(n.typeRef(), merged);
            case TsonMissing n -> n;
        };
    }

    // --- kind ---

    default boolean isRecord()    { return false; }
    default boolean isMap()       { return false; }
    default boolean isArray()     { return false; }
    default boolean isTuple()     { return false; }
    default boolean isAtom()      { return false; }
    default boolean isAbsent()    { return false; }
    default boolean isMissing()   { return false; }
    default boolean isContainer() { return isRecord() || isMap() || isArray() || isTuple(); }

    /**
     * The RFC 6901 pointer at which navigation failed (relative to the node it started from), or empty for
     * any node that is really there -- the {@link TsonMissing#path()} of a missing, without the cast.
     */
    default Optional<String> missingPath() { return Optional.empty(); }

    // --- navigation (never throws; TsonMissing when not applicable or absent) ---

    /** The field/entry named {@code name} (record/map), or a {@link TsonMissing} pointing at that step. */
    default TsonValue get(String name) { return TsonMissing.atField(name); }

    /** The element at {@code index} (array/tuple), or a {@link TsonMissing} pointing at that step. */
    default TsonValue get(int index) { return TsonMissing.atIndex(index); }

    /** The record fields (name → node, insertion order), or an empty map. */
    default Map<String, TsonValue> fields() { return Map.of(); }

    /** The array/tuple elements in order, or an empty list. */
    default List<TsonValue> elements() { return List.of(); }

    /**
     * RFC 6901 JSON Pointer navigation from this node: {@code ""} is this node itself, {@code "/a/b"} steps
     * into fields/indices, and any absent step yields {@link TsonMissing} (so the whole chain is null-safe).
     * A token that parses as an integer indexes an array/tuple; anything else names a field/entry.
     *
     * <p>The missing returned for a failed chain carries the pointer <b>up to and including the step that
     * failed</b>, not the whole pointer asked for -- {@code at("/a/b/c")} over a tree with no {@code b}
     * reports {@code "/a/b"}. Walking stops there: the remaining tokens have nothing left to step into, and
     * their outcome would say nothing about the document.
     *
     * @throws IllegalArgumentException if {@code pointer} is non-empty and doesn't start with {@code '/'}
     */
    default TsonValue at(String pointer) {
        if (pointer.isEmpty()) {
            return this;
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("a non-empty JSON Pointer must start with '/': \"" + pointer + "\"");
        }
        if (isMissing()) {
            return this; // already records where navigation failed; a later step can only be less informative
        }
        TsonValue current = this;
        int from = 1;
        while (from <= pointer.length()) {
            int slash = pointer.indexOf('/', from);
            int end = slash < 0 ? pointer.length() : slash;
            // RFC 6901 unescape: ~1 -> / first, then ~0 -> ~ (order matters, so ~01 decodes to ~1, not /).
            String token = pointer.substring(from, end).replace("~1", "/").replace("~0", "~");
            current = current.step(token);
            if (current.isMissing()) {
                // The step's own one-token path is relative to its receiver; re-anchor it to this node. The
                // source text is already escaped, so the prefix needs no re-escaping.
                return new TsonMissing(pointer.substring(0, end));
            }
            from = end + 1;
        }
        return current;
    }

    /** One pointer step: an integer token indexes an array/tuple, anything else names a field/entry. */
    private TsonValue step(String token) {
        if (isArray() || isTuple()) {
            try {
                return get(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                return TsonMissing.atField(token);
            }
        }
        return get(token);
    }

    // --- value (empty for a non-atom / TsonMissing; TsonAtom overrides as) ---

    /** This node's value cast to {@code type} if it's an atom holding an instance of it, else empty. */
    default <T> Optional<T> as(Class<T> type) { return Optional.empty(); }

    default Optional<String> asString()         { return as(String.class); }
    default Optional<Boolean> asBoolean()       { return as(Boolean.class); }
    default Optional<Number> asNumber()         { return as(Number.class); }
    default Optional<BigInteger> asBigInteger() { return as(BigInteger.class); }
    default Optional<BigDecimal> asBigDecimal() { return as(BigDecimal.class); }

    // --- numeric conveniences (converting, unlike the as* above, which only cast) ---

    /**
     * This node's value as an {@code int} if it is a number that fits exactly, else empty.
     *
     * <p><b>Exact, so nothing is silently lost.</b> A fractional part that <em>is</em> integral converts
     * ({@code 123.0} and {@code 234.56E2} both give an {@code int}); a real one ({@code 345.6}) does not.
     * A magnitude outside {@code int} range fails rather than saturating or wrapping. Text is never parsed:
     * {@code "42"} is a string per §4.4, and {@link #asString()} is what reads it.
     *
     * <p>These convert where {@link #asBigInteger()}/{@link #asBigDecimal()} only cast — an {@code int32}
     * field whose host value is an {@code Integer} answers {@code asInt()} but not {@code asBigInteger()}.
     */
    default OptionalInt asInt() {
        Optional<BigDecimal> decimal = asExactDecimal();
        if (decimal.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(decimal.get().intValueExact());
        } catch (ArithmeticException notExact) {
            return OptionalInt.empty();
        }
    }

    /** This node's value as a {@code long} if it is a number that fits exactly, else empty. See {@link #asInt()}. */
    default OptionalLong asLong() {
        Optional<BigDecimal> decimal = asExactDecimal();
        if (decimal.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(decimal.get().longValueExact());
        } catch (ArithmeticException notExact) {
            return OptionalLong.empty();
        }
    }

    /**
     * This node's value as a finite {@code double} if it is a number, else empty.
     *
     * <p>Rounding to the nearest {@code double} is accepted -- that is what a binary floating-point
     * accessor <em>means</em>, and demanding exactness would reject {@code 0.1}. What is not accepted is a
     * magnitude too large to be finite: that yields empty rather than {@code Infinity}, so an
     * out-of-range value can never read back as a plausible one.
     */
    default OptionalDouble asDouble() {
        Optional<BigDecimal> decimal = asExactDecimal();
        if (decimal.isEmpty()) {
            return OptionalDouble.empty();
        }
        double value = decimal.get().doubleValue();
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    /**
     * This node's numeric value as a {@code BigDecimal}, the one form every exactness question above can be
     * asked of, or empty when the node isn't a finite number.
     *
     * <p>Each host type contributes the decimal it <em>prints</em> as rather than its exact binary
     * expansion, so a {@code float} {@code 0.1} is {@code 0.1} and not {@code 0.100000001490116119384765625}
     * -- the shortest form that round-trips is the one an author wrote and a writer emits.
     */
    private Optional<BigDecimal> asExactDecimal() {
        return asNumber().flatMap(TsonValue::toDecimal);
    }

    private static Optional<BigDecimal> toDecimal(Number number) {
        return switch (number) {
            case BigDecimal value -> Optional.of(value);
            case BigInteger value -> Optional.of(new BigDecimal(value));
            case Double value -> Double.isFinite(value) ? Optional.of(BigDecimal.valueOf(value)) : Optional.empty();
            case Float value -> Float.isFinite(value) ? Optional.of(new BigDecimal(value.toString())) : Optional.empty();
            case Byte value -> Optional.of(BigDecimal.valueOf(value.longValue()));
            case Short value -> Optional.of(BigDecimal.valueOf(value.longValue()));
            case Integer value -> Optional.of(BigDecimal.valueOf(value.longValue()));
            case Long value -> Optional.of(BigDecimal.valueOf(value));
            // An atom parser may hand back any Number; take its printed form when that is a decimal at all,
            // so a host type this model doesn't enumerate (AtomicLong, a custom fixed-point) still reads.
            default -> {
                try {
                    yield Optional.of(new BigDecimal(number.toString()));
                } catch (NumberFormatException notADecimal) {
                    yield Optional.empty();
                }
            }
        };
    }
}
