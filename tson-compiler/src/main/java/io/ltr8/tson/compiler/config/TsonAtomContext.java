package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.UUID;

/**
 * The default built-in-vocabulary atom registrations every {@link DataBindContext} consumer in
 * this library needs (UUID/byte[]/LocalDate/OffsetTime/OffsetDateTime/URI/Inet4Address/
 * Inet6Address) -- shared by {@code io.ltr8.tson.compiler.mapper.TsonMapperContext} and this
 * package's own {@code SchemaMetaNameBinder}'s {@link #registerDefaults}-based {@code
 * defaultContext()}. Lives here, alongside the other configuration/wiring classes ("how a caller
 * configures a working environment," not `base`'s own §4 base-type-resolution machinery a consumer
 * never names directly) -- `mapper` reaching into `config` for this one class is a new, harmless
 * edge (`config` has no dependency back on `mapper`, so no cycle).
 */
public final class TsonAtomContext {

    private TsonAtomContext() {
    }

    public static DataBindContext defaultContext() {
        return registerDefaults(DataBindContext.builder().build());
    }

    /**
     * Applies this library's own default atom registrations to an already-built {@code context} --
     * the same registrations {@link #defaultContext()} applies to a fresh, unconfigured one -- so a
     * caller who needs a {@link DataBindContext.Builder} setting {@link #defaultContext()} doesn't
     * expose (e.g. object-binding mode's own {@code compiler.SchemaMetaNameBinder#defaultContext},
     * which also configures a {@code DataNameBinder}) can still get the same base registrations
     * without duplicating this list.
     */
    public static DataBindContext registerDefaults(DataBindContext context) {
        try {
            context.registerAtom(UUID.class);
            context.registerAtom(byte[].class);
            context.registerAtom(LocalDate.class);
            context.registerAtom(OffsetTime.class);
            context.registerAtom(OffsetDateTime.class);
            context.registerAtom(URI.class);
            context.registerAtom(Inet4Address.class);
            context.registerAtom(Inet6Address.class);
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to register default atom types on a fresh DataBindContext", e);
        }
        return context;
    }
}
