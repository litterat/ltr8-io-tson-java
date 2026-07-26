package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.DataValue;

/**
 * A compiled, schema-backed parser for one resolved type -- the Class 2 analogue of {@code
 * io.ltr8.tson.parser.resolver.vocab.AtomType}, generalized from atoms to every {@link
 * io.ltr8.tson.schema.meta.Top} kind. One instance is compiled per {@link TsonCompiledSchema} entry
 * (see its own Javadoc); unlike consulting the resolved schema's {@code Map<String,
 * TypeDefinition>} directly, a compiled instance already knows its own child parsers as real
 * object references (see {@link ParserHandle}), so reading a value at this position never
 * re-consults the schema's own name-keyed map at read time -- except at the specific edges that
 * close a cycle, where {@link ParserHandle.Indirect} does exactly one lazy lookup.
 *
 * <p>Takes a {@link DataValue}, not a bare {@code CoreValue}/{@code TokenValue} -- a schema
 * position can carry its own annotations and an explicit type-ref same as any other data position
 * (§2.3-§2.4), and a composite parser (record/array/map/tuple/choice) needs both, the same reason
 * {@code TsonMapperReader}'s own {@code toRecord}/{@code toArray}/etc. all take {@code DataValue}
 * rather than a narrower {@code CoreValue} subtype.
 *
 * <p>Deliberately read-only for now -- {@code write} (the serialization direction) isn't sketched
 * yet. It isn't obviously symmetric the way {@code AtomType.write} is: a hypothetical
 * validation-mode {@link TsonParserFactory} (object-binding/DOM/validation -- see {@link
 * TsonParserFactoryRegistry}'s own Javadoc) would produce a result that was never meant to be written
 * back out at all, so a single {@code write(T)} on this interface would be meaningless for at
 * least one of the parser families this is meant to cover. Left for a later pass once a
 * non-object-binding factory set actually exists to design against -- only {@link RecordParser}
 * (object-shaped) and the atom-family factories exist today.
 *
 * @param <T> the host value this parser produces -- an atom's natural host type for an atom-family
 *            parser (unaffected by which mode is compiling, see {@link TsonParserFactoryRegistry}), or
 *            whatever the compiling {@link TsonParserFactory} chose to produce for a composite: a
 *            bound Java object, a generic DOM-shaped value, a validation result, and so on.
 */
public interface TsonSchemaTypeParser<T> {

    T read(DataValue value);
}
