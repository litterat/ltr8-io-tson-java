package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UuidType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChoiceDisjointness#derive}: the §5.4 disjointness derivation over choice variants -- total and
 * two-valued. {@code true} exactly when every variant has a discrimination class (null / boolean / number /
 * string / brace / bracket) and no class appears twice; {@code false} otherwise, with no third state.
 *
 * <p>Alongside the rules, this pins the deliberate losses: value-set facts the class model does not consult
 * (separated numeric bounds, disjoint I-Regexp patterns, disjoint enum member sets, records separated by a
 * required field) all derive {@code false}, because no single form-resolution pass can act on them and the
 * labelled form (§5.11) is the recommended spelling where they arise.
 */
class ChoiceDisjointnessTest {

    private final Map<String, TypeDefinition> namespace = new LinkedHashMap<>();

    private TypeRef atom(String name, Top body, String... supertypes) {
        namespace.put(name, new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(supertypes), List.of(), Optional.empty(), body));
        return TypeRef.of(name);
    }

    private TypeRef product(String name, Top body) {
        namespace.put(name, new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of(), List.of(), Optional.empty(), body));
        return TypeRef.of(name);
    }

    private boolean disjoint(TypeRef... variants) {
        return ChoiceDisjointness.derive(new ChoiceBody(List.of(variants)), namespace);
    }

    private static IntegerType boundedInteger(Long min, Long max) {
        return new IntegerType(Optional.empty(), Optional.ofNullable(min).map(BigInteger::valueOf),
                Optional.empty(), Optional.ofNullable(max).map(BigInteger::valueOf), Optional.empty(),
                Optional.empty());
    }

    // ---------------------------------------------------------------- distinct classes

    @Test
    void scalarsOfDistinctClassesAreDisjoint() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        assertTrue(disjoint(i, t));
    }

    @Test
    void aContainerAndAScalarAreDisjoint() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef p = product("point", RecordBody.of(List.of()));
        assertTrue(disjoint(i, p));
    }

    @Test
    void aBraceAndABracketVariantAreDisjoint() {
        atom("text", TextType.UNCONSTRAINED);
        TypeRef record = product("point", RecordBody.of(List.of()));
        TypeRef array = product("names", new ArrayBody(TypeRef.of("text"), ElementState.REQUIRED, false, false,
                Optional.empty(), Optional.empty()));
        assertTrue(disjoint(record, array));
    }

    /** An enum's class is its members' shared base-type class, so {@code [true false]} occupies boolean. */
    @Test
    void aBooleanMemberedEnumOccupiesTheBooleanClass() {
        TypeRef flag = atom("flag", new EnumBody(List.of("true", "false")));
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        assertTrue(disjoint(flag, i, t));
    }

    @Test
    void oneSameClassPairMakesTheWholeChoiceNotDisjoint() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        TypeRef u = atom("id", new UuidType(Optional.empty()));
        // integer vs text: distinct; but uuid and text are both string-class -> whole choice false.
        assertFalse(disjoint(i, t, u));
    }

    // ---------------------------------------------------------------- one number class

    /**
     * The headline rule: every numeric family is one class, so separated bounds prove nothing -- recovering
     * positive from negative would take the type-directed second inspection [TSON-DATA] §2.4 forbids.
     */
    @Test
    void sameFamilyNumericsWithSeparatedBoundsAreNotDisjoint() {
        atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef positive = atom("positive_integer", boundedInteger(1L, null), "integer");
        TypeRef negative = atom("negative_integer", boundedInteger(null, -1L), "integer");
        assertFalse(disjoint(positive, negative));
    }

    @Test
    void differentNumericFamiliesShareTheNumberClass() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef d = atom("decimal", new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        TypeRef f = atom("float64", new FloatType(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), true, true, true, true));
        assertFalse(disjoint(i, d));
        assertFalse(disjoint(i, f));
        assertFalse(disjoint(d, f));
    }

    /** IS-A needs no rule of its own: a subtype shares its supertype's class, so same-class catches it. */
    @Test
    void isaVariantsAreNotDisjoint() {
        atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef positive = atom("positive_integer", boundedInteger(1L, null), "integer");
        assertFalse(disjoint(positive, TypeRef.of("integer")));
    }

    // ---------------------------------------------------------------- one string class

    @Test
    void stringFormAtomFamiliesShareTheStringClass() {
        TypeRef u = atom("id", new UuidType(Optional.empty()));
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        assertFalse(disjoint(u, t));
    }

    /** Pattern-separated texts are a value-set fact the class model deliberately does not consult. */
    @Test
    void textAtomsWithDisjointPatternsAreNotDisjoint() {
        TypeRef letters = atom("code", new TextType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("[a-z]+")));
        TypeRef digits = atom("tag", new TextType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("[0-9]+")));
        assertFalse(disjoint(letters, digits));
    }

    /** Disjoint member sets are equally out of reach: two name-membered enums are both string-class. */
    @Test
    void enumsWithDisjointMemberSetsAreNotDisjoint() {
        TypeRef warm = atom("warm", new EnumBody(List.of("red", "orange")));
        TypeRef cool = atom("cool", new EnumBody(List.of("blue", "green")));
        assertFalse(disjoint(warm, cool));
    }

    // ---------------------------------------------------------------- one brace class, one bracket class

    /** Records separated by a required field are separated as value sets, but not by their wire form. */
    @Test
    void twoRecordsAreNotDisjointHoweverTheirFieldsDiffer() {
        atom("text", TextType.UNCONSTRAINED);
        TypeRef circle = product("circle", RecordBody.of(List.of(
                RecordField.required("radius", TypeRef.of("text")))));
        TypeRef square = product("square", RecordBody.of(List.of(
                RecordField.required("side", TypeRef.of("text")))));
        assertFalse(disjoint(circle, square));
    }

    /** {@code {}} is an empty record and an empty map alike, so the two share the brace class. */
    @Test
    void aRecordAndAMapAreNotDisjoint() {
        atom("text", TextType.UNCONSTRAINED);
        TypeRef record = product("point", RecordBody.of(List.of()));
        TypeRef map = product("lookup", new MapBody(TypeRef.of("text"), TypeRef.of("text"), ElementState.REQUIRED, Optional.empty(), Optional.empty()));
        assertFalse(disjoint(record, map));
    }

    @Test
    void anArrayAndATupleAreNotDisjoint() {
        atom("text", TextType.UNCONSTRAINED);
        TypeRef array = product("names", new ArrayBody(TypeRef.of("text"), ElementState.REQUIRED, false, false,
                Optional.empty(), Optional.empty()));
        TypeRef tuple = product("pair", new TupleBody(List.of()));
        assertFalse(disjoint(array, tuple));
    }

    // ---------------------------------------------------------------- classless variants

    /** A rational's typed forms straddle §4 classes, so it has no class and its choice is never disjoint. */
    @Test
    void aClasslessAtomMakesTheChoiceNotDisjoint() {
        TypeRef r = atom("ratio", new RationalType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        assertFalse(disjoint(r, t));
    }

    @Test
    void aUnitVariantMakesTheChoiceNotDisjoint() {
        TypeRef token = atom("token", new Unit());
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        assertFalse(disjoint(token, i));
    }

    @Test
    void aMixedClassEnumMakesTheChoiceNotDisjoint() {
        TypeRef mixed = atom("mixed", new EnumBody(List.of("true", "red")));
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        assertFalse(disjoint(mixed, i));
    }

    @Test
    void anUnresolvedVariantMakesTheChoiceNotDisjoint() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        assertFalse(disjoint(i, TypeRef.of("missing")));
    }

    /** A nested choice spans classes, so it has none of its own -- the outer tag stays required. */
    @Test
    void aNestedChoiceVariantMakesTheChoiceNotDisjoint() {
        atom("integer", IntegerType.UNCONSTRAINED);
        atom("text", TextType.UNCONSTRAINED);
        namespace.put("inner", new TypeDefinition(Optional.empty(), TypeKind.SUM, List.of(), false,
                List.of(), List.of(), Optional.empty(),
                new ChoiceBody(List.of(TypeRef.of("integer"), TypeRef.of("text")))));
        TypeRef record = product("point", RecordBody.of(List.of()));
        assertFalse(disjoint(TypeRef.of("inner"), record));
    }

    // ---------------------------------------------------------------- references

    /** §8.3 makes an alias and its target one type, so a variant classifies through its reference chain. */
    @Test
    void anAliasVariantClassifiesAsItsTarget() {
        atom("integer", IntegerType.UNCONSTRAINED);
        namespace.put("my_int", TypeDefinition.reference("integer"));
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        assertTrue(disjoint(TypeRef.of("my_int"), t));
        assertFalse(disjoint(TypeRef.of("my_int"), TypeRef.of("integer")));
    }

    @Test
    void aReferenceCycleHasNoClassSoItsChoiceIsNotDisjoint() {
        namespace.put("a", TypeDefinition.reference("b"));
        namespace.put("b", TypeDefinition.reference("a"));
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        assertFalse(disjoint(TypeRef.of("a"), t));
    }
}
