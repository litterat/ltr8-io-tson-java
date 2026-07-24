package io.ltr8.tson.parser.mapper;

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
 * The default {@link DataBindContext} shared by {@link TsonMapperReader}'s and {@link
 * TsonMapperWriter}'s own no-arg constructors -- pulled into one spot so the built-in-vocabulary
 * atom registrations both directions need are listed once, not duplicated across two classes that
 * could drift apart.
 *
 * <p>{@code UUID} is §5.5's {@code uuid} atom's natural host type, but {@code tson-bind} can't
 * pre-register it itself -- {@code tson-bind} is deliberately TSON-agnostic (not even in the
 * {@code io.ltr8.tson} package tree), and {@code java.util.UUID} being a JDK class means it also
 * can't self-declare {@code @Atom} the way a hand-written class could. Registering it here, rather
 * than in {@code DataBindContext}'s own constructor, keeps that general-purpose library's default
 * atom set free of TSON-specific decisions -- a caller supplying their own {@code DataBindContext}
 * (e.g. to register a {@code DataBridge} for {@code Rational}/{@code Complex}, see their Javadoc)
 * is free to register {@code UUID} on their own terms instead, including with a bridge to a
 * different representation.
 *
 * <p>{@code byte[]} gets the same treatment, for a related but distinct reason: it's the natural
 * host type for all four §5.3 binary atoms ({@code base64}/{@code base64url}/{@code base32}/
 * {@code hex}), but {@code byte[].isArray()} is {@code true}, so {@code DefaultClassBinder}'s
 * array auto-detection claims it ahead of the atom/vocabulary path the same way real records
 * claim {@link io.ltr8.tson.schema.meta.Rational}/{@link
 * io.ltr8.tson.parser.resolver.vocab.Complex} -- but unlike those two, there's no competing
 * richer type a caller would plausibly want to defer to instead (§5.3's host value is
 * unconditionally "byte array"), so pre-registering it by default is the right call here, not
 * just a workaround.
 *
 * <p>{@code LocalDate}/{@code OffsetTime}/{@code OffsetDateTime} (§5.4's {@code date}/{@code
 * time}/{@code datetime}) are the same story as {@code UUID}: ordinary JDK classes, not records
 * or arrays, so no auto-detection collision, but also unable to self-declare {@code @Atom}, so
 * pre-registered here rather than left to fail. {@code IsoDuration} (§5.4's {@code duration}) is
 * the opposite story, matching {@code Rational}/{@code Complex}: it's this library's own record,
 * so it collides with record auto-detection the same way they do, and isn't pre-registered here
 * for the same reason they aren't -- a coarse pairing of {@link java.time.Period}/{@link
 * java.time.Duration} is a defensible default representation, but not obviously the *only* one a
 * caller would want, so binding it requires an explicit {@code DataBridge} rather than assuming
 * one opinionated shape.
 *
 * <p>{@code java.net.URI} (§5.5's {@code uri}) is the same story as {@code UUID}/{@code
 * LocalDate}: an ordinary JDK class, so pre-registered here rather than left to fail.
 *
 * <p>{@code Inet4Address} (§5.5's {@code ipv4}) is the same story again -- {@link
 * io.ltr8.tson.parser.resolver.vocab.Ipv4Parser#read} always returns exactly that subtype, so
 * that's what's registered here. Unlike {@code AtomType.read(TokenValue, Class)}'s own
 * target-narrowing check (which does accept a supertype, via {@code isInstance}), {@code
 * DataBindContext}'s registry is keyed by exact {@code Class}, so a field must be declared as
 * {@code Inet4Address} itself, not the broader {@code InetAddress}, to bind directly here.
 * {@code Inet6Address} (§5.5's {@code ipv6}) is registered for the identical reason -- {@link
 * io.ltr8.tson.parser.resolver.vocab.Ipv6Parser#read} always returns exactly that subtype too,
 * including for IPv4-mapped input text (see its Javadoc on why that needs its own care).
 */
final class TsonMapperContext {

    private TsonMapperContext() {
    }

    static DataBindContext defaultContext() {
        DataBindContext context = DataBindContext.builder().build();
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
