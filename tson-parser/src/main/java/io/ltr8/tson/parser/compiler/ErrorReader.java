package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;

/**
 * A stand-in {@link TsonValueReader} for a constructor with no compiled parser implemented yet --
 * this package's own copy of {@code compiler.ErrorReader}. Registering one directly (see {@link
 * ValueReaderFactoryRegistry}'s own trailing, clearly-marked block of these) lets a schema that
 * merely *declares* one of these constructors still compile successfully; only actually {@link
 * #read}ing a value against this specific entry fails, and only then. A caller who never reads this
 * entry never sees the failure at all.
 */
final class ErrorReader implements TsonValueReader<Object> {

    private final String name;
    private final RuntimeException cause;

    ErrorReader(String name, RuntimeException cause) {
        this.name = name;
        this.cause = cause;
    }

    @Override
    public Object read(DataValue value) {
        throw new UnsupportedOperationException("'" + name + "' has no usable compiled parser -- "
                + "the schema itself compiled fine, but nothing can read a value against this type: "
                + cause.getMessage(), cause);
    }
}
