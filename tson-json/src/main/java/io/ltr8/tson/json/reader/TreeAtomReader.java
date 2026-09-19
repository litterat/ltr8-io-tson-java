package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

/**
 * Tree mode's atom position: the family's parser runs, and the JSON value comes back unchanged.
 *
 * <p><b>The parse is the validation, and its result is deliberately discarded.</b> A schema-directed tree read
 * answers <em>does this document conform</em> -- {@code tson-cli} validating a JSON document against a TSON
 * schema keeps only the diagnostics -- so running {@code date}'s contract over {@code "2026-07-01"} is the
 * whole point and the {@code LocalDate} it produces is not wanted. A caller who wants the typed value is
 * asking a different question and reads in bind mode, where a class says what to build.
 *
 * <p><b>A set's element keeps one thing of it: its identity</b> ({@link #keyed}). [TSON-SCHEMA] §7.5 judges a
 * set's duplicates on the element's value space, and the node holds only its spelling -- two spellings of one
 * instant are two nodes and one element. The element read for a set answers the node with the parsed value's
 * {@link ValueIdentity} beside it; every other position pays nothing for it.
 *
 * <p>That is also why this yields a {@code JsonValue} and never a {@code TsonValue}: converting an encoding
 * is a different operation from reading one, and a JSON read hands back JSON.
 *
 * <p><b>A refused value yields {@link JsonNull}</b>, which is tree mode's placeholder rather than a claim
 * about the document -- the diagnostic beside it is what says what was wrong. It is the same answer
 * {@code tson-compiler}'s tree mode gives ({@code TsonAbsent}) in the same position, which is what keeps one
 * schema giving one shape of answer over both encodings.
 */
final class TreeAtomReader implements JsonTypeReader<Object> {

    /** Wraps any atom-family factory so its leaf yields the node the document carried. */
    static ValueReaderFactory over(ValueReaderFactory delegate) {
        return (name, definition, context) -> new TreeAtomReader(delegate.create(name, definition, context), false);
    }

    /**
     * The reader a set's elements are read at: an atom's answers its node {@link ValueIdentity.Identified} by
     * the value the parse produced. Any other element is compound, and its node is all a tree read has.
     */
    static JsonTypeReader<?> keyed(JsonTypeReader<?> element) {
        return element instanceof TreeAtomReader atom ? new TreeAtomReader(atom.delegate, true) : element;
    }

    private final JsonTypeReader<?> delegate;
    private final boolean keyed;

    private TreeAtomReader(JsonTypeReader<?> delegate, boolean keyed) {
        this.delegate = delegate;
        this.keyed = keyed;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        JsonEvent event = ctx.peek();
        JsonValue node = Nodes.scalar(event);
        int before = ctx.reported();
        Object value = delegate.read(ctx);
        // A composite where a scalar was due: the delegate reported it and consumed the whole value, so there
        // is no node to hand back and the placeholder is all this position has.
        if (node == null || ctx.reported() > before) {
            return JsonNull.INSTANCE;
        }
        return keyed ? new ValueIdentity.Identified(node, ValueIdentity.of(value)) : node;
    }
}
