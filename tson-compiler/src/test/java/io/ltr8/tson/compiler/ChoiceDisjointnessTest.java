package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ChoiceDisjointness#derive}: the §5.4 disjointness derivation over choice variants. Three-valued --
 * {@code true} (proved disjoint), {@code false} (provably not disjoint), absent (neither proved). Covers the
 * baseline rules this implementation attempts (different kind, different atom family, same-family integer
 * bounds, IS-A) and confirms the deliberately-unattempted cases (two same-family non-integer atoms) fall to
 * absent rather than a guess.
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

    private Optional<Boolean> disjoint(TypeRef... variants) {
        return ChoiceDisjointness.derive(new ChoiceBody(List.of(variants)), namespace);
    }

    private static IntegerType sized(int bits, boolean signed) {
        return new IntegerType(new IntegerSize(bits, signed));
    }

    private static IntegerType min(long min) {
        return new IntegerType(Optional.empty(), Optional.of(BigInteger.valueOf(min)),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static IntegerType max(long max) {
        return new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.valueOf(max)), Optional.empty(), Optional.empty());
    }

    @Test
    void differentAtomFamiliesAreDisjoint() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        assertEquals(Optional.of(true), disjoint(i, t));
    }

    @Test
    void differentKindsAreDisjoint() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef p = product("point", new RecordBody(List.of(), List.of(), List.of()));
        assertEquals(Optional.of(true), disjoint(i, p));
    }

    @Test
    void isaVariantsAreNotDisjoint() {
        atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef positive = atom("positive_integer", min(1), "integer");
        TypeRef integer = TypeRef.of("integer");
        assertEquals(Optional.of(false), disjoint(positive, integer));
    }

    @Test
    void sameFamilyIntegersWithDisjointRangesAreDisjoint() {
        TypeRef positive = atom("positive_integer", min(1), "integer");
        TypeRef negative = atom("negative_integer", max(-1), "integer");
        assertEquals(Optional.of(true), disjoint(positive, negative));
    }

    @Test
    void sameFamilyIntegersWithOverlappingRangesAreNotDisjoint() {
        TypeRef i32 = atom("int32", sized(32, true), "integer");
        TypeRef i64 = atom("int64", sized(64, true), "integer");
        // Neither is a declared subtype of the other, so this is decided purely by bound intervals:
        // int32's range sits inside int64's, so they share values.
        assertEquals(Optional.of(false), disjoint(i32, i64));
    }

    @Test
    void twoSameFamilyNonIntegerAtomsAreLeftAbsent() {
        TypeRef a = atom("code", new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("[a-z]+")));
        TypeRef b = atom("tag", new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("[0-9]+")));
        // Two text atoms are the same string base-type class -- pattern disjointness is a §5.4 MAY this
        // derivation doesn't attempt (TSON text can't discriminate a shared base class anyway).
        assertEquals(Optional.empty(), disjoint(a, b));
    }

    @Test
    void aChoiceIsDisjointOnlyIfEveryPairIs() {
        TypeRef i = atom("integer", IntegerType.UNCONSTRAINED);
        TypeRef t = atom("text", TextType.UNCONSTRAINED);
        TypeRef i64 = atom("int64", sized(64, true), "integer");
        // integer vs text: disjoint; but integer vs int64 (int64 IS-A integer): not disjoint -> whole choice false.
        assertEquals(Optional.of(false), disjoint(i, t, i64));
        // integer vs text vs a product: all pairwise disjoint -> true.
        TypeRef p = product("point", new RecordBody(List.of(), List.of(), List.of()));
        assertEquals(Optional.of(true), disjoint(i, t, p));
    }
}
