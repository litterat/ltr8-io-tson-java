package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link Top}/{@link Atom}/{@link Product}/{@link Sum} hierarchy actually replicates
 * the kernel's own composition chain (`atom => top & {}`, `product => top & { ... }`, `sum => top &
 * {}`, `reference => top & { target: type_name }`, Part 2 §4.1) as real Java subtyping.
 *
 * <p>There's no need to also assert e.g. {@code !(unit instanceof Product)} -- {@code Product}'s
 * own {@code permits} list doesn't name {@link Unit} (a {@code final} record), so the compiler
 * already rejects that check as provably impossible at compile time, a stronger guarantee than a
 * runtime assertion would give.
 */
class TypeBodyKindHierarchyTest {

    @Test
    void atomFamilyVariantsAreAtomAndTop() {
        assertInstanceOf(Atom.class, new Unit());
        assertInstanceOf(Top.class, new Unit());

        EnumBody members = new EnumBody(List.of("true", "false"));
        assertInstanceOf(Atom.class, members);
        assertInstanceOf(Top.class, members);
    }

    @Test
    void productFamilyVariantsAreProductAndTop() {
        RecordBody record = RecordBody.of(List.of());
        ArrayBody array = ArrayBody.of(TypeRef.of("integer"));
        MapBody map = MapBody.of(TypeRef.of("text"), TypeRef.of("text"));
        TupleBody tuple = new TupleBody(List.of());

        for (TypeBody body : List.of(record, array, map, tuple)) {
            assertInstanceOf(Product.class, body);
            assertInstanceOf(Top.class, body);
        }
    }

    @Test
    void sumFamilyVariantsAreSumAndTop() {
        ChoiceBody choice = new ChoiceBody(List.of(TypeRef.of("email"), TypeRef.of("phone")));
        assertInstanceOf(Sum.class, choice);
        assertInstanceOf(Top.class, choice);
    }

    @Test
    void referenceIsTopDirectlyNotThroughAnyBaseKind() {
        // reference => top & { target: type_name } -- composes with top directly, not through
        // atom/product/sum, so Reference implements Top but is sealed out of all three base kinds
        // (Reference isn't in Atom's/Product's/Sum's own permits lists).
        Reference reference = new Reference(TypeRef.of("token"));
        assertInstanceOf(Top.class, reference);
    }

    @Test
    void everyVariantIsBothATypeBodyAndATop() {
        // The two hierarchies are deliberately separate (see Top's own Javadoc) but every leaf
        // variant implements both.
        List<TypeBody> allVariants = List.of(
                RecordBody.of(List.of()),
                new Reference(TypeRef.of("token")),
                new Unit(),
                new EnumBody(List.of("a", "b")),
                new ChoiceBody(List.of(TypeRef.of("a"), TypeRef.of("b"))),
                ArrayBody.of(TypeRef.of("integer")),
                MapBody.of(TypeRef.of("text"), TypeRef.of("text")),
                new TupleBody(List.of()));
        for (TypeBody body : allVariants) {
            assertTrue(body instanceof Top, body.getClass().getSimpleName() + " should be a Top");
        }
    }
}
