package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;

/**
 * A stand-in {@link TsonTypeReader} for a constructor with no compiled reader implemented yet --
 * this package's own copy of {@code reader.ErrorReader}. Registering one directly (see {@link
 * ValueReaderFactoryRegistry}'s own trailing, clearly-marked block of these) lets a schema that
 * merely *declares* one of these constructors still compile successfully; only actually {@link
 * #read}ing a value against this specific entry fails, and only then. A caller who never reads this
 * entry never sees the failure at all.
 *
 * <p><b>Always throws, ignoring {@code ctx} entirely -- deliberately, even in collecting mode.</b>
 * This represents a library/schema-compile gap ("no reader implemented for this constructor yet"),
 * not a per-document data problem; a caller can't fix it by correcting their data, so silently
 * collecting it as one more {@link io.ltr8.tson.compiler.Diagnostic} among many would be misleading.
 */
final public class ErrorReader implements TsonTypeReader<Object> {

    private final String name;
    private final RuntimeException cause;

    public ErrorReader(String name, RuntimeException cause) {
        this.name = name;
        this.cause = cause;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        throw new UnsupportedOperationException("'" + name + "' has no usable compiled reader -- "
                + "the schema itself compiled fine, but nothing can read a value against this type: "
                + cause.getMessage(), cause);
    }
}
