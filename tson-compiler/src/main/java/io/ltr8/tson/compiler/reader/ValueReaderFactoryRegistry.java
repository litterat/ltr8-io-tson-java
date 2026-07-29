package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code constructor name -> ValueReaderFactory} table, one per mode -- {@link #dom()}/{@link
 * #bind(DataBindContext)} are the two instances a caller actually wants.
 *
 * <p><b>Fully self-contained within this package</b> -- every entry is either this package's own
 * composite factory ({@code record}/{@code array}/{@code map}/{@code tuple}) or one of {@link
 * AtomValueReader}'s own constants, this package's own copy of the atom-family adapters. No
 * dependency on {@code reader.TsonParserFactoryRegistry} (which is going away) or anything else in
 * {@code reader} -- deliberate, while this package's own shape is still settling.
 *
 * <p><b>{@code set}/{@code vector}/{@code array_min}/{@code array_max}/{@code array_ranged} all
 * register to the exact same {@code array} factory instance</b> -- every one of them resolves to an
 * {@code ArrayBody} regardless of which produced a given instance ({@code set}/{@code vector} via
 * refinement, per meta-kernel.tn1/meta.tn1; {@code array_min}/{@code array_max}/{@code array_ranged}
 * aren't {@code ~}-marked constructors at all, but are kept registered here too for the same
 * lookup-by-name convenience), so there's no separate shape to build a distinct factory for. This is
 * a *lookup-by-constructor-name* convenience only -- it says nothing about whether an {@code
 * array}-typed position should ever dispatch to {@code !set [...]} at read time, which {@link
 * RecordBindReader.Factory}'s own Javadoc deliberately does not attempt for any composite kind but
 * {@code record}.
 *
 * <p><b>{@code enum}/{@code boolean} is the one case each mode resolves differently</b> -- {@link
 * AtomValueReader#ENUM_OBJECT_MODE} for {@link #bind} (itself dispatching {@code boolean} to a real
 * {@code Boolean} via {@link BooleanReader}, every other member name through the ordinary path),
 * {@link AtomValueReader#ENUM} for {@link #dom()} (uniformly {@code String}, no target Java type to
 * reconcile against).
 *
 * <p><b>{@code choice} is shared between both modes</b>, registered once via {@link
 * ChoiceReader#FACTORY} -- see that class's own Javadoc for why it has no {@code
 * DataClassUnion}-bounded counterpart the way {@code record} does.
 *
 * <p><b>Every {@code ~}-marked constructor meta-kernel.tn1/meta.tn1 declare has an entry</b> --
 * verified against both files directly, not assumed. Six of them (see {@link #notImplemented}'s own
 * call sites, deliberately grouped at the bottom of {@link #baseFactories} rather than interleaved
 * with the working entries above) still have no compiled reader at all -- {@code extern}, {@code
 * unknown_type}, {@code email_type}, {@code cidr4_type}, {@code cidr6_type}, {@code mac_type} --
 * registered anyway, to an {@link ErrorReader}, so a schema merely *declaring* one of them still
 * compiles; only reading a value against one actually fails. {@code complex_type}/{@code
 * ipv4_type}/{@code ipv6_type} used to be in this group too -- wired up to the real {@code
 * atom.ComplexParser}/{@code Ipv4Parser}/{@code Ipv6Parser}, which already existed but had never
 * been registered anywhere.
 */
public final class ValueReaderFactoryRegistry implements ValueReaderFactoryResolver {

    private final Map<String, ValueReaderFactory> factories;

    private ValueReaderFactoryRegistry(Map<String, ValueReaderFactory> factories) {
        this.factories = factories;
    }

    @Override
    public ValueReaderFactory resolve(String name) {
        ValueReaderFactory factory = factories.get(name);
        if (factory == null) {
            throw new IllegalStateException("no ValueReaderFactory registered for constructor '" + name + "'");
        }
        return factory;
    }

    public static ValueReaderFactoryRegistry dom() {
        return new ValueReaderFactoryRegistry(baseFactories(
                new RecordDomReader.Factory(), new ArrayDomReader.Factory(), new MapDomReader.Factory(),
                new TupleDomReader.Factory(), AtomValueReader.ENUM));
    }

    public static ValueReaderFactoryRegistry bind(DataBindContext context) {
        return new ValueReaderFactoryRegistry(baseFactories(
                new RecordBindReader.Factory(context), new ArrayBindReader.Factory(context),
                new MapBindReader.Factory(context), new TupleBindReader.Factory(context),
                AtomValueReader.ENUM_OBJECT_MODE));
    }

    private static Map<String, ValueReaderFactory> baseFactories(ValueReaderFactory record, ValueReaderFactory array,
            ValueReaderFactory map, ValueReaderFactory tuple, ValueReaderFactory enumFactory) {
        Map<String, ValueReaderFactory> factories = new LinkedHashMap<>();

        // meta-kernel.tn1
        factories.put("unit", AtomValueReader.UNIT);
        factories.put("integer_type", AtomValueReader.INTEGER_TYPE);
        factories.put("text_type", AtomValueReader.TEXT_TYPE);
        factories.put("uri_type", AtomValueReader.URI_TYPE);
        factories.put("regex_type", AtomValueReader.REGEX_TYPE);
        factories.put("record", record);
        factories.put("array", array);
        factories.put("set", array);
        factories.put("map", map);
        factories.put("tuple", tuple);
        factories.put("enum", enumFactory);
        factories.put("choice", ChoiceReader.FACTORY);

        // meta.tn1
        factories.put("binary", AtomValueReader.BINARY);
        factories.put("vector", array);
        factories.put("float_type", AtomValueReader.FLOAT_TYPE);
        factories.put("decimal_type", AtomValueReader.DECIMAL_TYPE);
        factories.put("rational_type", AtomValueReader.RATIONAL_TYPE);
        factories.put("date_type", AtomValueReader.DATE_TYPE);
        factories.put("time_type", AtomValueReader.TIME_TYPE);
        factories.put("datetime_type", AtomValueReader.DATETIME_TYPE);
        factories.put("duration_type", AtomValueReader.DURATION_TYPE);
        factories.put("uuid_type", AtomValueReader.UUID_TYPE);
        factories.put("complex_type", AtomValueReader.COMPLEX_TYPE);
        factories.put("ipv4_type", AtomValueReader.IPV4_TYPE);
        factories.put("ipv6_type", AtomValueReader.IPV6_TYPE);

        // Sugar/alias names -- not their own `~`-marked constructors, kept for lookup convenience only.
        factories.put("array_min", array);
        factories.put("array_max", array);
        factories.put("array_ranged", array);

        // ---- Not implemented yet -- every entry below is a real `~`-marked constructor from
        // ---- meta-kernel.tn1/meta.tn1 with no compiled reader at all. Registered to ErrorReader so a
        // ---- schema declaring one still compiles; only reading a value against one actually fails.
        factories.put("extern", notImplemented("extern"));
        factories.put("unknown_type", notImplemented("unknown_type"));
        factories.put("email_type", notImplemented("email_type"));
        factories.put("cidr4_type", notImplemented("cidr4_type"));
        factories.put("cidr6_type", notImplemented("cidr6_type"));
        factories.put("mac_type", notImplemented("mac_type"));

        // Collections.unmodifiableMap, not Map.copyOf -- preserves the LinkedHashMap's own insertion
        // order (Map.copyOf's own iteration order is unspecified), so the "not implemented" block
        // stays visibly last at runtime too, not just in source.
        return Collections.unmodifiableMap(factories);
    }

    private static ValueReaderFactory notImplemented(String constructorName) {
        return (name, typeDefinition, resolver) -> new ErrorReader(name, new UnsupportedOperationException(
                "'" + name + "' uses the '" + constructorName + "' constructor, which has no compiled reader "
                        + "implemented yet"));
    }
}
