package io.ltr8.tson.mapper;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.TsonWriter;
import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.MapValue;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.BaseTypeResolver;
import io.ltr8.tson.parser.resolver.BaseValue;
import io.ltr8.tson.parser.resolver.vocab.AtomType;
import io.ltr8.tson.parser.resolver.vocab.AtomTypeException;
import io.ltr8.tson.parser.resolver.vocab.BinaryType;
import io.ltr8.tson.parser.resolver.vocab.BuiltinTypeVocabulary;
import io.ltr8.tson.parser.resolver.vocab.Complex;
import io.ltr8.tson.parser.resolver.vocab.ComplexType;
import io.ltr8.tson.parser.resolver.vocab.DateTimeType;
import io.ltr8.tson.parser.resolver.vocab.DateType;
import io.ltr8.tson.parser.resolver.vocab.DurationType;
import io.ltr8.tson.parser.resolver.vocab.Ipv4Type;
import io.ltr8.tson.parser.resolver.vocab.Ipv6Type;
import io.ltr8.tson.parser.resolver.vocab.IsoDuration;
import io.ltr8.tson.parser.resolver.vocab.Rational;
import io.ltr8.tson.parser.resolver.vocab.RationalType;
import io.ltr8.tson.parser.resolver.vocab.TimeType;
import io.ltr8.tson.parser.resolver.vocab.UriType;
import io.ltr8.tson.parser.resolver.vocab.UuidType;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Binds a parsed TSON {@link DataValue} tree to a Java object, given its {@link DataClass}
 * descriptor from {@code tson-bind} -- and back. Operates directly against {@link DataBindContext}
 * (the same level {@code MapMapper}/{@code ArrayMapper} in {@code tson-bind} operate at), not
 * against any bigger schema/type-registry layer: TSON's own schema layer (Part 2) doesn't exist
 * yet, so there's nothing above {@code DataBindContext} for this to need.
 *
 * <p>Unlike {@code litterat-json}'s {@code JsonMapper}, this reads from an already-parsed AST
 * (the {@code Parser} has already built the full tree) rather than a live token stream, so there's
 * no need to buffer array elements into a temporary list first -- {@code ArrayValue.elements()}
 * is already a concrete {@code List}.
 *
 * <p>Atom binding first checks whether the value carries a type-ref at all. If it does, {@link
 * BuiltinTypeVocabulary} must resolve it (§5) -- an unrecognized type-ref is a binding error here,
 * not silently ignored, even though the Class 1 processing step underneath (§5.1) is required to
 * (and does, in {@code tson-parser}'s {@code Parser}/{@code BaseTypeResolver}) preserve an
 * unrecognized annotation as an uninterpreted marker rather than erroring -- that rule is about
 * passive preservation during parsing, not about what an application actively binding the value to
 * a caller-declared Java type should do with a marker it can't interpret; see SPEC-FEEDBACK.md #7.
 * A resolved built-in type does identification, validation, and narrowing to the target class in
 * one call ({@link AtomType#read(TokenValue, Class)}), since it alone knows both its own parsing
 * contract and (per the target {@code Class<?>} passed in) how to narrow to it. With no type-ref at
 * all, binding falls through to plain untyped resolution: {@link BaseTypeResolver} (identification:
 * which of null/boolean/number/string, and for numbers which of the four §7.6 grammar forms) then
 * {@link AtomBinder} (binding: that identified shape into whatever concrete Java type the target
 * field actually declares). Both paths end up sharing the same narrowing code ({@code
 * NumberNarrowing}, in {@code tson-parser}) one level down, so a plain {@code 42} and a {@code
 * !uint8 42} bind through the same final step regardless of which path found them.
 */
public final class TsonMapper {

    private final DataBindContext context;

    /**
     * The reverse of {@link BuiltinTypeVocabulary}'s name-&gt;{@code AtomType} map: which {@code
     * AtomType} (and under what type-ref name) writes a given bound Java class's values, for {@link
     * #toTson}. Curated by hand, not derived from {@code BuiltinTypeVocabulary} wholesale -- the
     * integer/decimal/float family has no unique reverse mapping at all (many names, e.g. {@code
     * int8}..{@code int256}, can bind to the same host type, so a bound {@code long} carries no way
     * to know which one -- or whether it was typed at all -- produced it; see {@link #toTson}'s own
     * Javadoc), so those stay on the generic default-atom path instead of appearing here.
     *
     * <p>A plain mutable instance field, not a shared static table -- deliberately, so a future
     * caller wanting to extend the vocabulary with their own {@code AtomType} (registering it here
     * the same way {@code DataBindContext#registerAtom} already lets a caller extend the read side)
     * has an actual per-mapper map to add to, not a global one to work around.
     */
    private final Map<Class<?>, VocabularyAtom> vocabularyAtoms;

    public TsonMapper(DataBindContext context) {
        this.context = context;
        this.vocabularyAtoms = defaultVocabularyAtoms();
    }

    public TsonMapper() {
        this(defaultContext());
    }

    /** Pairs an {@code AtomType} with the type-ref name {@link #toTson} should write it under. */
    private record VocabularyAtom(String typeRef, AtomType<?> atomType) {
        @SuppressWarnings("unchecked")
        String write(Object value) {
            return ((AtomType<Object>) atomType).write(value);
        }
    }

    private static Map<Class<?>, VocabularyAtom> defaultVocabularyAtoms() {
        Map<Class<?>, VocabularyAtom> atoms = new HashMap<>();
        atoms.put(UUID.class, new VocabularyAtom(UuidType.TYPENAME, UuidType.UNCONSTRAINED));
        atoms.put(URI.class, new VocabularyAtom(UriType.TYPENAME, UriType.UNCONSTRAINED));
        atoms.put(Inet4Address.class, new VocabularyAtom(Ipv4Type.TYPENAME, Ipv4Type.UNCONSTRAINED));
        atoms.put(Inet6Address.class, new VocabularyAtom(Ipv6Type.TYPENAME, Ipv6Type.UNCONSTRAINED));
        atoms.put(LocalDate.class, new VocabularyAtom(DateType.TYPENAME, DateType.UNCONSTRAINED));
        atoms.put(OffsetTime.class, new VocabularyAtom(TimeType.TYPENAME, TimeType.UNCONSTRAINED));
        atoms.put(OffsetDateTime.class, new VocabularyAtom(DateTimeType.TYPENAME, DateTimeType.UNCONSTRAINED));
        // base64 is an arbitrary but reasonable default -- no way to recover which of
        // base64/base64url/base32/hex a byte[] was originally decoded from, that information
        // doesn't survive decoding (see #toTson's own Javadoc).
        atoms.put(byte[].class, new VocabularyAtom(BinaryType.BASE64.typeName(), BinaryType.BASE64));
        atoms.put(Rational.class, new VocabularyAtom(RationalType.TYPENAME, RationalType.UNCONSTRAINED));
        atoms.put(Complex.class, new VocabularyAtom(ComplexType.TYPENAME, ComplexType.UNCONSTRAINED));
        atoms.put(IsoDuration.class, new VocabularyAtom(DurationType.TYPENAME, DurationType.UNCONSTRAINED));
        return atoms;
    }

    /**
     * {@code UUID} is §5.5's {@code uuid} atom's natural host type, but {@code tson-bind} can't
     * pre-register it itself -- {@code tson-bind} is deliberately TSON-agnostic (not even in the
     * {@code io.ltr8.tson} package tree), and {@code java.util.UUID} being a JDK class means it also
     * can't self-declare {@code @Atom} the way a hand-written class could. Registering it here,
     * rather than in {@code DataBindContext}'s own constructor, keeps that general-purpose library's
     * default atom set free of TSON-specific decisions -- a caller supplying their own {@code
     * DataBindContext} (e.g. to register a {@code DataBridge} for {@code Rational}/{@code Complex},
     * see their Javadoc) is free to register {@code UUID} on their own terms instead, including with
     * a bridge to a different representation.
     *
     * <p>{@code byte[]} gets the same treatment, for a related but distinct reason: it's the natural
     * host type for all four §5.3 binary atoms ({@code base64}/{@code base64url}/{@code base32}/
     * {@code hex}), but {@code byte[].isArray()} is {@code true}, so {@code DefaultClassBinder}'s
     * array auto-detection claims it ahead of the atom/vocabulary path the same way real records
     * claim {@link io.ltr8.tson.parser.resolver.vocab.Rational}/{@link
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
     * io.ltr8.tson.parser.resolver.vocab.Ipv4Type#read} always returns exactly that subtype, so
     * that's what's registered here. Unlike {@link AtomType#read(TokenValue, Class)}'s own
     * target-narrowing check (which does accept a supertype, via {@code isInstance}), {@code
     * DataBindContext}'s registry is keyed by exact {@code Class}, so a field must be declared as
     * {@code Inet4Address} itself, not the broader {@code InetAddress}, to bind directly here.
     * {@code Inet6Address} (§5.5's {@code ipv6}) is registered for the identical reason -- {@link
     * io.ltr8.tson.parser.resolver.vocab.Ipv6Type#read} always returns exactly that subtype too,
     * including for IPv4-mapped input text (see its Javadoc on why that needs its own care).
     */
    private static DataBindContext defaultContext() {
        DataBindContext context = DataBindContext.builder().build();
        try {
            context.registerAtom(UUID.class);
            context.registerAtom(byte[].class);
            context.registerAtom(LocalDate.class);
            context.registerAtom(OffsetTime.class);
            context.registerAtom(OffsetDateTime.class);
            context.registerAtom(java.net.URI.class);
            context.registerAtom(java.net.Inet4Address.class);
            context.registerAtom(java.net.Inet6Address.class);
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to register default atom types on a fresh DataBindContext", e);
        }
        return context;
    }

    // ── Entry points ─────────────────────────────────────────────────────

    public <T> T toObject(String tsonSource, Class<T> targetClass) throws DataBindException {
        Document document = new Parser(tsonSource).parseDocument();
        return toObject(document.root(), targetClass);
    }

    @SuppressWarnings("unchecked")
    public <T> T toObject(DataValue value, Class<T> targetClass) throws DataBindException {
        DataClass dataClass = context.getDescriptor(targetClass);
        return (T) toObject(value, dataClass);
    }

    /**
     * Writes {@code value} as TSON text -- the reverse of {@link #toObject(String, Class)}, mainly
     * useful as a debugging tool (inspect what a bound object actually contains) rather than a
     * guaranteed-lossless serializer. Emits a {@code !typeName} type-ref only where one is actually
     * needed for the value to read back correctly: exactly the built-in vocabulary's JDK-backed
     * host types (uuid/uri/ipv4/ipv6/date/time/datetime/binary/rational/complex/duration), none of
     * which round-trip through default value resolution (§4) on their own. Everything default
     * resolution *does* already recover -- plain numbers, booleans, strings, {@code null} -- is
     * written bare, which means the integer family's exact width is **not** preserved: a field
     * bound from {@code !uint8 42} writes back as plain {@code 42}, indistinguishable from a field
     * that was never {@code !uint8}-typed at all. That's not a bug to fix -- a schemaless writer has
     * no way to know the width was ever there in the first place, the same reason a schemaless
     * reader has no way to reject an out-of-range value without the annotation.
     *
     * <p>{@code @Annotated}-captured wire-format annotations are not re-emitted yet -- deferred to
     * a follow-up, not part of this first pass (values only).
     */
    public String toTson(Object value) throws DataBindException {
        TsonWriter writer = new TsonWriter();
        if (value == null) {
            writer.nullValue();
            return writer.toString();
        }
        DataClass dataClass = context.getDescriptor(value.getClass());
        write(value, dataClass, writer);
        return writer.toString();
    }

    // ── Core dispatch ────────────────────────────────────────────────────

    private Object toObject(DataValue value, DataClass dataClass) throws DataBindException {
        try {
            Object result = switch (dataClass) {
                case DataClassAtom atom -> toAtom(value, atom);
                case DataClassRecord record -> toRecord(value, record);
                case DataClassArray array -> toArray(value, array);
                case DataClassMap map -> toMap(value, map);
                case DataClassTuple tuple -> toTuple(value, tuple);
                case DataClassUnion union -> toUnion(value, union);
                default -> throw new DataBindException("unsupported DataClass: " + dataClass);
            };

            if (dataClass.bridge().isPresent()) {
                result = dataClass.bridge().get().toObject().invoke(result);
            }
            return result;
        } catch (DataBindException e) {
            throw e;
        } catch (Throwable t) {
            throw new DataBindException("failed to bind value to " + dataClass.typeClass(), t);
        }
    }

    /** {@code null} if {@code value} is either genuinely absent (missing) or the TSON absent sentinel {@code _}. */
    private static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    /**
     * The write-side mirror of {@link #toObject(DataValue, DataClass)}. {@code null} (the base
     * type, distinct from omitting the field entirely -- see {@link #writeRecord}) aside: a bridge,
     * if present, is unwrapped once, up front -- covers plain Java {@code enum}s and {@code
     * Rational}/{@code Complex}/{@code IsoDuration} reached through a caller's own {@code
     * DataBridge} (all via {@code DataBindContext#registerAtom(Class, DataBridge)}, which always
     * attaches to a {@code DataClassAtom}), and, in principle, a {@code DataClassRecord}'s own
     * (unrelated) {@code ToData}-interface bridge too, the same way {@code litterat-json}'s {@code
     * JsonMapper.toJson} handles both uniformly -- {@code dataClass} itself is reused unchanged
     * afterward, not re-resolved, since a bridged {@code DataClassRecord}'s fields are already
     * resolved against the bridge's own data type (see {@code DefaultRecordBinder#resolveRecord}),
     * not the original wrapper type. Then dispatches on {@code dataClass}'s own kind.
     */
    private void write(Object value, DataClass dataClass, TsonWriter writer) throws DataBindException {
        try {
            if (value == null) {
                writer.nullValue();
                return;
            }
            if (dataClass.bridge().isPresent()) {
                value = dataClass.bridge().get().toData().invoke(value);
            }
            switch (dataClass) {
                case DataClassAtom atom -> writeAtom(value, writer);
                case DataClassRecord record -> writeRecord(value, record, writer);
                case DataClassArray array -> writeArray(value, array, writer);
                case DataClassMap map -> writeMap(value, map, writer);
                case DataClassTuple tuple -> writeTuple(value, tuple, writer);
                case DataClassUnion union -> writeUnion(value, union, writer);
                default -> throw new DataBindException("unsupported DataClass for writing: " + dataClass);
            }
        } catch (DataBindException e) {
            throw e;
        } catch (Throwable t) {
            throw new DataBindException("failed to write value of type " + dataClass.typeClass(), t);
        }
    }

    /**
     * {@code value} is already unwrapped from any bridge by the time it gets here (see {@link
     * #write}) -- a lookup in {@link #vocabularyAtoms} decides the rest: found means one of the
     * built-in vocabulary's known host types, written with its own type-ref (see {@link #toTson});
     * not found falls back to {@link AtomWriter#writeDefaultAtom}. An enum's bridge produces a
     * plain {@code String}, which lands in that fallback and so writes as a quoted string rather
     * than unquoted -- both read back identically, not worth a special case purely for that
     * formatting difference.
     */
    private void writeAtom(Object value, TsonWriter writer) throws DataBindException {
        VocabularyAtom vocab = vocabularyAtoms.get(value.getClass());
        if (vocab != null) {
            writer.typeRef(vocab.typeRef()).quotedString(vocab.write(value));
        } else {
            AtomWriter.writeDefaultAtom(value, writer);
        }
    }

    // ── Atoms: built-in vocabulary (§5) or identification (BaseTypeResolver) + binding (AtomBinder) ──

    private Object toAtom(DataValue value, DataClassAtom dataClass) throws DataBindException {
        if (isAbsent(value)) {
            return AtomBinder.bind(new BaseValue.NullValue(), dataClass.dataClass());
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof TokenValue token)) {
            throw new DataBindException("expected a token for " + dataClass.typeClass() + ", found " + core);
        }

        Optional<String> typeRef = value.typeRef();
        if (typeRef.isPresent()) {
            // Unlike the Class 1 processing step underneath us (Parser/BaseTypeResolver, which
            // correctly preserves an unrecognized type-ref as an uninterpreted marker per §5.1),
            // an unresolvable annotation on a value we're actively binding to a caller-declared
            // Java type is treated as an error here, not silent fallthrough -- see
            // SPEC-FEEDBACK.md #7. A typo like !Uuid (case-sensitive per §5.1) should be loud, not
            // quietly disable the validation the author clearly wanted.
            AtomType<?> atomType = BuiltinTypeVocabulary.lookup(typeRef.get())
                    .orElseThrow(() -> new DataBindException("unrecognized type annotation '!"
                            + typeRef.get() + "' for " + dataClass.typeClass()));
            return bindBuiltin(atomType, token, dataClass.dataClass());
        }

        BaseValue resolved = BaseTypeResolver.resolve(token);
        return AtomBinder.bind(resolved, dataClass.dataClass());
    }

    private static Object bindBuiltin(AtomType<?> atomType, TokenValue token, Class<?> target) throws DataBindException {
        try {
            return atomType.read(token, target);
        } catch (AtomTypeException e) {
            // §5.2's parse/validation distinction doesn't need to survive past this boundary today --
            // both are just "this value doesn't satisfy its own declared type" from a binding caller's
            // perspective -- but the underlying AtomParseException/AtomValidationException is preserved
            // as the cause for anyone who wants to distinguish them.
            throw new DataBindException(e.getMessage(), e);
        } catch (ArithmeticException e) {
            throw new DataBindException(token.text() + " does not fit in " + target, e);
        } catch (IllegalArgumentException e) {
            throw new DataBindException("cannot bind '" + token.text() + "' to " + target, e);
        }
    }

    // ── Records ──────────────────────────────────────────────────────────

    /**
     * A field marked {@code @Annotated} (io.ltr8.annotation) isn't bound from a same-named
     * authored field at all -- it's populated directly from {@code value.annotations()}, the
     * annotations on this record's *own* value (e.g. {@code @doc:"..." !person { name: Alice } }'s
     * {@code @doc}), which is exactly what {@link DataClassField#isAnnotationsCarrier()} exists to
     * flag before the ordinary by-name lookup below ever runs for it. {@code tson-bind} can't
     * validate the component's declared type is {@link TsonAnnotations} itself (no dependency on
     * this module), so that check happens here, at the one place both are visible.
     */
    private Object toRecord(DataValue value, DataClassRecord dataClass) throws Throwable {
        Map<String, DataValue> byName = new HashMap<>();
        CoreValue core = value.coreValue();
        if (core instanceof RecordValue rv) {
            // "Last value wins" for duplicate field names falls out naturally: iterating in
            // source order and overwriting on put() matches the spec's own rule (§2.5).
            for (RecordValue.Field f : rv.fields()) {
                byName.put(f.name(), f.value().value());
            }
        } else if (!(core instanceof EmptyBrace)) {
            throw new DataBindException("expected a record for " + dataClass.typeClass() + ", found " + core);
        }

        DataClassField[] fields = dataClass.fields();
        Object[] construct = new Object[fields.length];
        for (DataClassField field : fields) {
            if (field.isAnnotationsCarrier()) {
                if (field.type() != TsonAnnotations.class) {
                    throw new DataBindException("@Annotated component '" + field.name() + "' on "
                            + dataClass.typeClass() + " must be of type TsonAnnotations, found " + field.type());
                }
                construct[field.index()] = new TsonAnnotations(value.annotations());
                continue;
            }
            DataValue fieldValue = byName.get(field.name());
            if (isAbsent(fieldValue)) {
                if (field.isRequired()) {
                    throw new DataBindException(
                            "missing required field '" + field.name() + "' for " + dataClass.typeClass());
                }
                construct[field.index()] = null;
            } else {
                construct[field.index()] = toObject(fieldValue, field.dataClass());
            }
        }
        return dataClass.constructor().invoke(construct);
    }

    /**
     * {@code @Annotated}-captured annotations aren't re-emitted yet (see {@link #toTson}) -- that
     * field is skipped entirely here, not written as though it were an ordinary structural value.
     * A field that isn't present ({@code Optional.empty()}, or a plain reference field holding
     * {@code null} -- both read the same way on the way in, via {@link DataClassField#isPresent}) is
     * omitted from the record entirely rather than written as {@code null}, matching how the two
     * cases are already treated identically on the read side.
     */
    private void writeRecord(Object value, DataClassRecord dataClass, TsonWriter writer) throws Throwable {
        writer.beginRecord();
        for (DataClassField field : dataClass.fields()) {
            if (field.isAnnotationsCarrier() || !field.isPresent(value)) {
                continue;
            }
            writer.field(field.name());
            write(field.get(value), field.dataClass(), writer);
        }
        writer.endRecord();
    }

    // ── Arrays ───────────────────────────────────────────────────────────

    private Object toArray(DataValue value, DataClassArray dataClass) throws Throwable {
        CoreValue core = value.coreValue();
        if (!(core instanceof ArrayValue av)) {
            throw new DataBindException("expected an array for " + dataClass.typeClass() + ", found " + core);
        }
        List<ScopedValue> elements = av.elements();

        Object arrayData = dataClass.constructor().invoke(elements.size());
        Object iterator = dataClass.iterator().invoke(arrayData);
        DataClass elementClass = dataClass.arrayDataClass();

        for (ScopedValue element : elements) {
            Object bound = toObject(element.value(), elementClass);
            dataClass.put().invoke(arrayData, iterator, bound);
        }
        return arrayData;
    }

    private void writeArray(Object value, DataClassArray dataClass, TsonWriter writer) throws Throwable {
        writer.beginArray();
        int size = (int) dataClass.size().invoke(value);
        Object iterator = dataClass.iterator().invoke(value);
        DataClass elementClass = dataClass.arrayDataClass();
        for (int i = 0; i < size; i++) {
            Object element = dataClass.get().invoke(value, iterator);
            writer.beforeArrayElement();
            write(element, elementClass, writer);
        }
        writer.endArray();
    }

    // ── Maps ─────────────────────────────────────────────────────────────

    /**
     * A map key is a full {@code data-value} (§2.6), not just a token, so it's bound recursively
     * through {@link #toObject(DataValue, DataClass)} exactly like a value is -- there's nothing
     * map-specific about interpreting a key beyond that. "Last value wins" for a duplicate key
     * falls out for free from repeated {@code put()} calls in source order, the same way {@link
     * #toRecord} gets record field deduplication for free (§2.5/§2.6) -- key *equality* here is
     * whatever the bound key type's own {@code equals()}/{@code hashCode()} say it is, which for a
     * plain {@code String} key naturally matches the resolver-layer's textual comparison, but isn't
     * guaranteed to for an arbitrary bound key type.
     *
     * <p>{@code {}} parses as {@link EmptyBrace}, not {@link MapValue} -- resolving which typed
     * container an empty {@code {}} denotes is a deferred resolver-layer concern (§2.8), the same
     * reason {@link #toRecord} special-cases it. Treated as zero entries here, the same reasonable
     * default {@code toRecord} uses for zero fields.
     *
     * <p>§2.9: the absent sentinel {@code _} "MUST NOT appear as a map key -- a resolver-layer
     * constraint, not a grammar constraint: the map-entry production accepts any value in key
     * position, and the resolver rejects absent keys." The structural parser correctly allows
     * {@code { _ => 1 } } through (confirmed by {@code ParserTest}) since that's a grammar-level
     * permission, not a resolver one -- this is the one place that resolver-layer rejection
     * actually happens, since nothing between the parser and here is positioned to enforce it.
     */
    private Object toMap(DataValue value, DataClassMap dataClass) throws Throwable {
        CoreValue core = value.coreValue();
        List<MapValue.MapEntry> entries;
        if (core instanceof MapValue mv) {
            entries = mv.entries();
        } else if (core instanceof EmptyBrace) {
            entries = List.of();
        } else {
            throw new DataBindException("expected a map for " + dataClass.typeClass() + ", found " + core);
        }

        Object mapData = dataClass.constructor().invoke(entries.size());
        DataClass keyClass = dataClass.keyDataClass();
        DataClass valueClass = dataClass.valueDataClass();

        for (MapValue.MapEntry entry : entries) {
            if (entry.key().coreValue() instanceof AbsentValue) {
                throw new DataBindException(
                        "the absent sentinel '_' must not appear as a map key (§2.9) for " + dataClass.typeClass());
            }
            Object key = toObject(entry.key(), keyClass);
            Object boundValue = toObject(entry.value().value(), valueClass);
            dataClass.put().invoke(mapData, key, boundValue);
        }
        return mapData;
    }

    private void writeMap(Object value, DataClassMap dataClass, TsonWriter writer) throws Throwable {
        writer.beginMap();
        Object iterator = dataClass.iterator().invoke(value);
        DataClass keyClass = dataClass.keyDataClass();
        DataClass valueClass = dataClass.valueDataClass();
        Object entry;
        while ((entry = dataClass.next().invoke(iterator)) != null) {
            Object key = dataClass.key().invoke(entry);
            Object entryValue = dataClass.value().invoke(entry);
            writer.beforeMapEntry();
            write(key, keyClass, writer);
            writer.mapArrow();
            write(entryValue, valueClass, writer);
        }
        writer.endMap();
    }

    // ── Tuples ───────────────────────────────────────────────────────────

    /**
     * A tuple is array-shaped on the wire, not record-shaped ({@code io.ltr8.annotation.Tuple}'s
     * own Javadoc: the meta-kernel's {@code tuple} is a {@code product} like {@code record}, but
     * with array's {@code INDEX} access pattern instead of {@code NAMED}) -- so unlike {@link
     * #toRecord}, {@code {}} isn't a plausible reading here at all; only {@link ArrayValue}
     * applies, and TSON's empty array {@code []} is unambiguous already, so there's no
     * {@link EmptyBrace} case to special-case the way {@link #toRecord}/{@link #toMap} need.
     */
    private Object toTuple(DataValue value, DataClassTuple dataClass) throws Throwable {
        CoreValue core = value.coreValue();
        if (!(core instanceof ArrayValue av)) {
            throw new DataBindException("expected an array for tuple " + dataClass.typeClass() + ", found " + core);
        }
        List<ScopedValue> elements = av.elements();

        DataClassElement[] slots = dataClass.elements();
        if (elements.size() != slots.length) {
            throw new DataBindException("tuple " + dataClass.typeClass() + " has " + slots.length
                    + " elements, found " + elements.size());
        }

        Object[] construct = new Object[slots.length];
        for (int i = 0; i < slots.length; i++) {
            construct[i] = toObject(elements.get(i).value(), slots[i].dataClass());
        }
        return dataClass.constructor().invoke(construct);
    }

    /** No type-ref -- a tuple is array-shaped on the wire, and (schemaless) its tuple-ness at all
     * isn't recoverable without a schema any more than an integer's exact width is (see {@link
     * #toTson}); this writes a plain array, indistinguishable on the wire from an ordinary one. */
    private void writeTuple(Object value, DataClassTuple dataClass, TsonWriter writer) throws Throwable {
        writer.beginArray();
        for (DataClassElement element : dataClass.elements()) {
            writer.beforeArrayElement();
            write(element.accessor().invoke(value), element.dataClass(), writer);
        }
        writer.endArray();
    }

    // ── Unions ───────────────────────────────────────────────────────────

    /**
     * Disambiguated by the value's own type annotation (§3.2's {@code !typeName}) -- TSON has a
     * first-class way to say "this value is specifically a Circle", unlike JSON, which needs an
     * ad hoc injected field for the same purpose. A member class's {@link Typename} annotation
     * gives the exact match; falling that, the member's simple class name matches
     * case-insensitively (so {@code !circle} matches a Java class named {@code Circle} without
     * requiring every fixture to be annotated).
     */
    private Object toUnion(DataValue value, DataClassUnion dataClass) throws Throwable {
        String typeName = value.typeRef().orElseThrow(() -> new DataBindException(
                "union type " + dataClass.typeClass() + " requires a type annotation (!typeName) to disambiguate members"));

        Class<?> member = resolveUnionMember(dataClass, typeName);
        DataClass memberDataClass = context.getDescriptor(member);

        // Strip the type-ref before recursing -- it named the member, not a further type for it.
        DataValue memberValue = new DataValue(value.annotations(), Optional.empty(), value.coreValue());
        return toObject(memberValue, memberDataClass);
    }

    private static Class<?> resolveUnionMember(DataClassUnion dataClass, String typeName) throws DataBindException {
        for (Class<?> member : dataClass.memberTypes()) {
            Typename tn = member.getAnnotation(Typename.class);
            if (tn != null && tn.name().equals(typeName)) {
                return member;
            }
        }
        for (Class<?> member : dataClass.memberTypes()) {
            if (member.getAnnotation(Typename.class) == null && member.getSimpleName().equalsIgnoreCase(typeName)) {
                return member;
            }
        }
        throw new DataBindException(
                "no member of union " + dataClass.typeClass() + " matches type name '" + typeName + "'");
    }

    /**
     * The reverse of {@link #resolveUnionMember}: given the value's own runtime class (necessarily
     * one specific member, not the union type itself), picks one canonical type-ref name for it --
     * {@link Typename} if present, else the simple class name -- rather than accepting either form
     * the way the read side does. Read/write asymmetry is fine here; a reader benefiting from
     * flexibility doesn't obligate a writer to be equally flexible about its own single output.
     */
    private void writeUnion(Object value, DataClassUnion dataClass, TsonWriter writer) throws Throwable {
        Class<?> memberClass = value.getClass();
        if (!dataClass.isMemberType(memberClass)) {
            throw new DataBindException(
                    "value of type " + memberClass + " is not a member of union " + dataClass.typeClass());
        }
        // Lowercased when falling back to the simple class name (not the @Typename value, used
        // verbatim) -- matches this codebase's own convention of lowercase type-refs, and the read
        // side's case-insensitive fallback match means either case reads back correctly regardless.
        Typename tn = memberClass.getAnnotation(Typename.class);
        writer.typeRef(tn != null ? tn.name() : memberClass.getSimpleName().toLowerCase(java.util.Locale.ROOT));
        write(value, context.getDescriptor(memberClass), writer);
    }
}
