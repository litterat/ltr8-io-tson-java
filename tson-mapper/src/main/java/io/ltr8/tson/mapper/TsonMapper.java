package io.ltr8.tson.mapper;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.BaseTypeResolver;
import io.ltr8.tson.parser.resolver.BaseValue;
import io.ltr8.tson.parser.resolver.vocab.AtomType;
import io.ltr8.tson.parser.resolver.vocab.AtomTypeException;
import io.ltr8.tson.parser.resolver.vocab.BuiltinTypeVocabulary;

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

    public TsonMapper(DataBindContext context) {
        this.context = context;
    }

    public TsonMapper() {
        this(defaultContext());
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

    // ── Core dispatch ────────────────────────────────────────────────────

    private Object toObject(DataValue value, DataClass dataClass) throws DataBindException {
        try {
            Object result = switch (dataClass) {
                case DataClassAtom atom -> toAtom(value, atom);
                case DataClassRecord record -> toRecord(value, record);
                case DataClassArray array -> toArray(value, array);
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
}
