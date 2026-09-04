package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.util.UUID;

/**
 * The default built-in-vocabulary atom registrations every {@link DataBindContext} consumer in
 * this library needs (UUID/byte[]/LocalDate/OffsetTime/OffsetDateTime/Duration/Period/URI/Inet4Address/
 * Inet6Address/CidrNetwork/SourcePosition) -- the shared {@code defaultContext()} that {@code TsonObjectReader}'s
 * and {@code TsonObjectWriter}'s own no-arg constructors, and this package's own {@code
 * SchemaMetaNameBinder}'s {@link #registerDefaults}-based {@code defaultContext()}, all delegate to.
 * Lives here, alongside the other configuration/wiring classes ("how a caller configures a working
 * environment," not `base`'s own §4 base-type-resolution machinery a consumer never names directly).
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
            // duration_type and period_type carry their bounds as values rather than tokens -- the two
            // families compare the value and not the spelling -- so both host types are components of
            // a schema.meta record and need registering, as the other temporal host types do.
            context.registerAtom(Duration.class);
            context.registerAtom(Period.class);
            context.registerAtom(URI.class);
            context.registerAtom(Inet4Address.class);
            context.registerAtom(Inet6Address.class);
            // An atom, not a record, though it is a Java record: cidr4/cidr6 read to one network value from
            // one token, and tson-bind's record auto-detection would otherwise make a `{ prefix: ...
            // prefixLength: ... }` of it and refuse the scalar the wire actually carries.
            context.registerAtom(io.ltr8.tson.schema.atom.CidrNetwork.class);
            // An atom, not a record: a Token *is* one token on the wire -- the text plus the form that
            // produced it -- where binding it structurally writes it as `{ text: ... form: ... }`, a record
            // where [TSON-SCHEMA] §8's resolved form has a scalar. Reading is unaffected (the slot's own
            // reader decides -- see RecordBindReader.tokenAware); this is what stops the writer inventing a
            // shape, and AtomWriter frames it from its own form.
            context.registerAtom(io.ltr8.tson.schema.meta.Token.class);
            // schema.meta.TypeDefinition.position is typed SourcePosition, not tson-compiler's own
            // Position, specifically so tson-schema never has to name it -- an interface with no
            // sealed/@Union signal tson-bind could auto-detect, so it needs this same explicit
            // registration every other non-auto-detectable atom-like type already gets.
            context.registerAtom(SourcePosition.class, new SourcePositionStringBridge());
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to register default atom types on a fresh DataBindContext", e);
        }
        return context;
    }
}
