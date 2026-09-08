package io.ltr8.tson.base.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.base.atom.CidrNetwork;

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
 * The host types the built-in atoms read to, registered with a {@link DataBindContext} -- what makes a
 * consumer's {@code UUID} or {@code LocalDate} component bind at all.
 *
 * <p><b>One vocabulary, every encoding.</b> [TSON-JSON] §5.1 hands a string's content to the atom's own
 * parser exactly as a TSON quoted token's text would be, so which families a reader can bind is a property
 * of the type system and not of the encoding that carried them. It lives here, beside
 * {@link io.ltr8.tson.base.atom the host values themselves}, because a consumer whose class has a
 * {@code UUID} component must not have to discover that one front door binds it and the other does not.
 *
 * <p>Every type here is registered as an <b>atom</b> rather than left to {@code tson-bind}'s structural
 * auto-detection. Most are opaque scalars the engine has no way to take apart; {@link CidrNetwork} is a Java
 * record, so auto-detection would make a {@code { prefix: ... prefixLength: ... }} of it and refuse the
 * scalar {@code cidr4}/{@code cidr6} actually carry.
 *
 * <p>{@code duration_type} and {@code period_type} carry their bounds as values rather than tokens -- the
 * two families compare the value and not the spelling -- so both host types are components of a resolved
 * schema record and need registering, as the other temporal host types do.
 *
 * <p><b>Registering a class is not the same as converting to it.</b> What this buys is that
 * {@code tson-bind} treats each as a scalar; the string-to-host-value conversion is the position's own atom
 * parser's, under a schema. With no schema neither encoding converts one, which is a shared gap rather than
 * an asymmetry -- {@code BACKLOG.md}'s "Binding" section carries it.
 *
 * <p><b>What is deliberately not here</b> is anything naming a type this module cannot see. Binding the
 * <em>resolved schema model</em> needs two more registrations -- {@code schema.meta.Token}, which is one
 * token on the wire where its record shape would write {@code { text: ... form: ... }}, and
 * {@code SourcePosition}, whose bridge names {@code tson-compiler}'s own {@code Position} -- and
 * {@code tson-compiler}'s {@code config.ResolverBindContext} applies both on top of this list. The split is
 * by what needs them: this is what a <em>consumer's</em> classes bind, and that is what binding this
 * library's own schema model additionally needs.
 */
public final class AtomContext {

    private AtomContext() {
    }

    /** A fresh context carrying the atom vocabulary and nothing else. */
    public static DataBindContext defaultContext() {
        return registerDefaults(DataBindContext.builder().build());
    }

    /**
     * Applies the atom vocabulary to an already-built {@code context} and returns it -- for a caller who
     * needed a {@link DataBindContext.Builder} to set something {@link #defaultContext()} does not expose,
     * typically a {@code DataNameBinder}, and still wants these registrations without restating them.
     */
    public static DataBindContext registerDefaults(DataBindContext context) {
        try {
            context.registerAtom(UUID.class);
            context.registerAtom(byte[].class);
            context.registerAtom(LocalDate.class);
            context.registerAtom(OffsetTime.class);
            context.registerAtom(OffsetDateTime.class);
            context.registerAtom(Duration.class);
            context.registerAtom(Period.class);
            context.registerAtom(URI.class);
            context.registerAtom(Inet4Address.class);
            context.registerAtom(Inet6Address.class);
            context.registerAtom(CidrNetwork.class);
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to register default atom types on a fresh DataBindContext", e);
        }
        return context;
    }
}
