package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.compiler.ValueReaderFactory;
import io.ltr8.tson.compiler.compiler.ValueReaderFactoryRegistry;

/**
 * Reads a value at one compiled, schema-known position -- the front door a caller actually holds
 * after compiling a schema, via {@link TsonCompiledSchema#get}. Lives at this module's own root
 * package, alongside {@link TsonDataParser}/{@link TsonSchemaParser} -- this library's other two
 * types a consumer directly names -- rather than in {@code compiler}, since a caller receives one
 * of these directly and never needs to know how it was built.
 *
 * <p>The Class 2 analogue of {@link AtomType}, generalized from atoms to every {@link
 * io.ltr8.tson.schema.meta.Top} kind. One instance is compiled per {@link TsonCompiledSchema} entry;
 * unlike consulting the resolved schema's {@code Map<String, TypeDefinition>} directly, a compiled
 * instance already knows its own child readers as real object references, so reading a value at
 * this position never re-consults the schema's own name-keyed map at read time -- except at the
 * specific edges that close a cycle, where a deferred, name-keyed lookup does exactly one.
 *
 * <p>Takes a {@link DataValue}, not a bare {@code CoreValue}/{@code TokenValue} -- a schema
 * position can carry its own annotations and an explicit type-ref same as any other data position
 * (§2.3-§2.4), and a composite reader (record/array/map/tuple/choice) needs both, the same reason
 * {@code TsonMapperReader}'s own {@code toRecord}/{@code toArray}/etc. all take {@code DataValue}
 * rather than a narrower {@code CoreValue} subtype.
 *
 * <p><b>Unchecked failures only, deliberately</b> -- every exception this whole read/parse stack
 * throws is a {@link RuntimeException} (the lexer's own {@code LexException}, {@code
 * TsonParseException}, {@code TsonSchemaValidationException}, and so on); the one checked exception
 * anywhere in this codebase, {@code tson-bind}'s own {@code DataBindException}, is confined to
 * compile/bind-time setup and never reaches a {@link #read} call. A checked exception here would
 * also propagate through every functional interface this reader composes through ({@link
 * ValueReaderFactory}, {@code CompilationContext}, {@code DataNameBinder}), which this codebase has
 * consistently avoided elsewhere for the same reason.
 *
 * <p>Deliberately read-only for now -- {@code write} (the serialization direction) isn't sketched
 * yet. It isn't obviously symmetric the way {@link AtomType#write} is: a validation-mode {@link
 * ValueReaderFactory} would produce a result that was never meant to be written back out at all, so
 * a single {@code write(T)} on this interface would be meaningless for at least one of the reader
 * families this is meant to cover. Left for a later pass once a non-object-binding factory set
 * actually exists to design against -- only DOM mode ({@code RecordDomReader}, object-shaped) and
 * object-binding mode's own atom-family factories exist today.
 *
 * @param <T> the host value this reader produces -- an atom's natural host type for an atom-family
 *            reader (unaffected by which mode is compiling, see {@link ValueReaderFactoryRegistry}), or
 *            whatever the compiling {@link ValueReaderFactory} chose to produce for a composite: a
 *            bound Java object, a generic DOM-shaped value, a validation result, and so on.
 */
public interface TsonValueReader<T> {

    T read(DataValue value);
}
