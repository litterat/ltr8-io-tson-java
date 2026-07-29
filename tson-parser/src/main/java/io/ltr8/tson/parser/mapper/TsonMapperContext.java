package io.ltr8.tson.parser.mapper;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.base.TsonAtomContext;

/**
 * The default {@link DataBindContext} shared by {@link TsonMapperReader}'s and {@link
 * TsonMapperWriter}'s own no-arg constructors -- pulled into one spot so the built-in-vocabulary
 * atom registrations both directions need are listed once, not duplicated across two classes that
 * could drift apart.
 *
 * <p><b>Delegates to {@link TsonAtomContext#defaultContext()}</b>, shared with object-binding
 * mode's own {@code config.SchemaMetaNameBinder#defaultContext}. See {@link TsonAtomContext}'s own
 * Javadoc for exactly which atoms are registered and why (UUID/byte[]/LocalDate/OffsetTime/
 * OffsetDateTime/URI/Inet4Address/Inet6Address).
 */
final class TsonMapperContext {

    private TsonMapperContext() {
    }

    static DataBindContext defaultContext() {
        return TsonAtomContext.defaultContext();
    }
}
