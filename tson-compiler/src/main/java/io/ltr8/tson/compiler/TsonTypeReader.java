package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.reader.ValueReaderFactory;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;

/**
 * Reads a value at one compiled, schema-known position -- the front door a caller actually holds
 * after compiling a schema, via {@link TsonCompiledSchema#get}. Lives at this module's own root
 * package, alongside {@link TsonDataParser}/{@link TsonSchemaParser} -- this library's other two
 * types a consumer directly names -- rather than in {@code reader}, since a caller receives one
 * of these directly and never needs to know how it was built.
 *
 * <p>The Class 2 analogue of {@link AtomType}, generalized from atoms to every {@link
 * io.ltr8.tson.schema.meta.Top} kind. One instance is compiled per {@link TsonCompiledSchema} entry;
 * unlike consulting the resolved schema's {@code Map<String, TypeDefinition>} directly, a compiled
 * instance already knows its own child readers as real object references, so reading a value at
 * this position never re-consults the schema's own name-keyed map at read time -- except at the
 * specific edges that close a cycle, where a deferred, name-keyed lookup does exactly one.
 *
 * <p><b>Pulls its own events from {@link TsonReadContext} rather than being handed an
 * already-materialized value</b> -- {@code ctx} wraps a {@code TsonEventSource} (in practice, almost
 * always a real {@code TsonDataStream}), so a large document never needs to be fully parsed into a
 * tree before schema-validated reading can begin; memory held at any point is proportional to
 * nesting depth, the same property {@code TsonDataStream} itself already has. {@code ctx} is also the
 * tree walk's own error sink, current path, and position tracking, shared across an entire read so a
 * problem at one field/element doesn't have to abort the whole read to be reported; see that
 * interface's own Javadoc.
 *
 * <p><b>One method, and no source or policy overloads -- this reads one value at {@code ctx}'s cursor and
 * nothing more.</b> Whole-document reading is not this interface's job: framing (consuming {@code
 * DocumentStart}, and pulling past the root value so a lazy {@code TsonDataStream} actually rejects trailing
 * content), the {@code !!schema} decision, and the choice of diagnostics receiver all belong to {@link
 * TsonTreeReader}/{@link TsonObjectReader}, which is what a consumer reads through -- including for a schema
 * held out of band, via their {@code withSchema(uri).readAs(source, typeName)}.
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
 * actually exists to design against -- only DOM mode ({@code RecordTreeReader}, object-shaped) and
 * object-binding mode's own atom-family factories exist today.
 *
 * @param <T> the host value this reader produces -- an atom's natural host type for an atom-family
 *            reader (unaffected by which mode is compiling, see {@link ValueReaderFactoryRegistry}), or
 *            whatever the compiling {@link ValueReaderFactory} chose to produce for a composite: a
 *            bound Java object, a generic DOM-shaped value, a validation result, and so on.
 */
public interface TsonTypeReader<T> {

    T read(TsonReadContext ctx);
}
