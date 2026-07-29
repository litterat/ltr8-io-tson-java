package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.ast.DataValue;

/**
 * A stand-in {@link TsonValueReader} for a constructor with no compiled reader implemented yet --
 * this package's own copy of {@code reader.ErrorReader}. Registering one directly (see {@link
 * ValueReaderFactoryRegistry}'s own trailing, clearly-marked block of these) lets a schema that
 * merely *declares* one of these constructors still compile successfully; only actually {@link
 * #read}ing a value against this specific entry fails, and only then. A caller who never reads this
 * entry never sees the failure at all.
 */
final public class ErrorReader implements TsonValueReader<Object> {

    private final String name;
    private final RuntimeException cause;

    public ErrorReader(String name, RuntimeException cause) {
        this.name = name;
        this.cause = cause;
    }

    @Override
    public Object read(DataValue value) {
        throw new UnsupportedOperationException("'" + name + "' has no usable compiled reader -- "
                + "the schema itself compiled fine, but nothing can read a value against this type: "
                + cause.getMessage(), cause);
    }
}
