package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;

/**
 * A stand-in {@link TsonValueReader} for a resolved entry whose own compiled parser couldn't
 * be built -- what {@link TsonSchemaCompiler}'s own per-entry build step falls back to, for exactly
 * one entry at a time, instead of letting a build failure anywhere in the schema abort compiling
 * the rest of it.
 * Two real causes so far, both exercised against real fixtures: no {@link TsonParserFactory}
 * registered at all for a constructor (e.g. core.tn1's own {@code cidr4}/{@code email}/... atom
 * families, which have no {@code atom} parser yet -- {@link
 * TsonParserFactoryRegistry#require} throwing {@code IllegalStateException}), and a factory that IS
 * registered but whose own eager validation rejects this particular entry (object-binding mode's
 * {@code TsonObjectBinder}, which deliberately never binds meta-kernel's own non-record-
 * bound marker entries like {@code top}/{@code atom} -- see that class's own Javadoc). Both surface
 * identically here: the schema as a whole still compiles; only {@link #read}ing an actual value
 * against this specific entry fails, and only then.
 *
 * <p>{@code cause}'s own message is preserved verbatim in the thrown exception -- this is a deferral,
 * not a swallow. A caller who never reads this entry never sees the failure at all.
 */
final class ErrorParser implements TsonValueReader<Object> {

    private final String name;
    private final RuntimeException cause;

    ErrorParser(String name, RuntimeException cause) {
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
