package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.vocab.AtomType;

/**
 * Adapts any {@code resolver.vocab} {@link AtomType} into a {@link TsonTypeParser} -- the one place
 * a compiled position backed by an atom-family constructor (§5.5's {@code integer_type}, {@code
 * text_type}, ...) bridges from {@link DataValue} (what every {@link TsonTypeParser} reads) down to
 * {@link TokenValue} (what {@link AtomType} itself reads).
 *
 * <p>Deliberately kept in this package, not {@code resolver.vocab} itself -- {@code resolver.vocab}
 * stays unaware the compiled-schema-parser layer exists at all, the same one-way direction it
 * already keeps toward {@code resolver.schema}: vocab is consumed by higher layers, it doesn't
 * import from them. Each atom-family {@link TsonParserFactory} (e.g. {@code
 * IntegerTypeParserFactory}, {@code EnumTypeParserFactory}) wraps its own {@code resolver.vocab}
 * parser in one of these rather than duplicating this bridging logic per family.
 *
 * <p>Package-private -- an implementation detail of how an atom-family {@link TsonParserFactory} is
 * built, not part of this package's own public surface ({@link TsonTypeParser}/{@link ParserHandle}/
 * {@link ParserFactoryRegistry}/{@link TsonSchemaParser}).
 */
final class AtomTypeParser<T> implements TsonTypeParser<T> {

    private final AtomType<T> delegate;

    AtomTypeParser(AtomType<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T read(DataValue value) {
        if (value == null) {
            // A genuinely missing (not merely absent-sentinel) value -- RecordParser already
            // guards this before ever calling a child's read(), but a caller reaching this
            // directly (or a future composite parser that doesn't guard) gets a clear error
            // instead of an NPE from value.coreValue() below.
            throw new IllegalArgumentException("expected a token for " + delegate + ", found no value");
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof TokenValue token)) {
            throw new IllegalArgumentException("expected a token for " + delegate + ", found " + core);
        }
        return delegate.read(token);
    }
}
