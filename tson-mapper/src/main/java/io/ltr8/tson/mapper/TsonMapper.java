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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>Atom binding goes through {@link BaseTypeResolver} (identification: which of
 * null/boolean/number/string, and for numbers which of the four §7.6 grammar forms) and then
 * {@link AtomBinder} (binding: that identified shape into whatever concrete Java type the target
 * field actually declares -- {@code int}, {@code BigInteger}, etc.). That split mirrors the one
 * already established in {@code tson-parser}'s resolver package; this is where the "binding" half
 * finally gets implemented, driven by a real target type instead of guessing at one.
 */
public final class TsonMapper {

    private final DataBindContext context;

    public TsonMapper(DataBindContext context) {
        this.context = context;
    }

    public TsonMapper() {
        this(DataBindContext.builder().build());
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

    // ── Atoms: identification (BaseTypeResolver) + binding (AtomBinder) ──

    private Object toAtom(DataValue value, DataClassAtom dataClass) throws DataBindException {
        if (isAbsent(value)) {
            return AtomBinder.bind(new BaseValue.NullValue(), dataClass.dataClass());
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof TokenValue token)) {
            throw new DataBindException("expected a token for " + dataClass.typeClass() + ", found " + core);
        }
        BaseValue resolved = BaseTypeResolver.resolve(token);
        return AtomBinder.bind(resolved, dataClass.dataClass());
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
