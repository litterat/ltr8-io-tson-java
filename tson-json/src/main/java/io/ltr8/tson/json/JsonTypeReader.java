package io.ltr8.tson.json;

/**
 * Reads a JSON value at one compiled, schema-known position -- what a caller holds after compiling a schema,
 * via {@link JsonCompiledSchema#get}. The peer of {@code tson-compiler}'s {@code TsonTypeReader}, and one of
 * this stack's own: {@code docs/json-encoding.md} carries why the schema-directed readers are {@code
 * tson-json}'s rather than that engine's, and why that is a deferral rather than a conclusion.
 *
 * <p><b>One method, and it reads one value at {@code ctx}'s cursor and nothing more.</b> Framing -- the
 * document's single root, the binding that says which schema and which root type ([TSON-JSON] §3.4), the
 * choice of diagnostics receiver -- belongs to the facade a consumer actually reads through, never here.
 *
 * <p><b>It pulls its own events rather than being handed a materialized value</b>, so a large document never
 * has to become a tree before it can be validated, and memory held at any point is proportional to nesting
 * depth. {@link JsonReadContext} is also the read's diagnostics sink, its RFC 6901 path, and its position at
 * both ends -- data and schema.
 *
 * <p><b>[TSON-JSON] §4.1 is what makes one reader per position the right unit.</b> Every rule in §5-§8 has
 * the shape <em>at a position whose effective type is T, JSON form F carries value V</em>, and nothing is
 * ever read speculatively -- so the position must be decided before the value's content is touched, and a
 * compiled reader is that decision, made once at compile time rather than per value.
 *
 * <p>Unchecked failures only, as everywhere in this library's read stacks: a problem with the document goes
 * to the receiver as a {@code Diagnostic}, and only a fault propagates as an exception.
 *
 * @param <T> what this reader produces -- an atom's host value, or whatever the compiling factory chose for
 *            a composite: a {@code JsonValue} node in tree mode, a bound Java object in bind mode.
 */
public interface JsonTypeReader<T> {

    T read(JsonReadContext ctx);
}
