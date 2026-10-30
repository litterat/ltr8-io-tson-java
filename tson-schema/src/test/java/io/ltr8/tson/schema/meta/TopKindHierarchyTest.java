package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link Top}/{@link Atom}/{@link Product}/{@link Sum} hierarchy actually replicates
 * the kernel's own composition chain (`atom => top & {}`, `product => top & { ... }`, `sum => top &
 * {}`, `alias => top & { target: type_ref }`, Part 2 §4.1) as real Java subtyping.
 *
 * <p>There's no need to also assert e.g. {@code !(unit instanceof Product)} -- {@code Product}'s
 * own {@code permits} list doesn't name {@link ValueType} (a {@code final} record), so the compiler
 * already rejects that check as provably impossible at compile time, a stronger guarantee than a
 * runtime assertion would give.
 */
class TopKindHierarchyTest {

    @Test
    void atomFamilyVariantsAreAtomAndTop() {
        assertInstanceOf(Atom.class, new ValueType());
        assertInstanceOf(Top.class, new ValueType());

        EnumBody members = new EnumBody(List.of("true", "false"));
        assertInstanceOf(Atom.class, members);
        assertInstanceOf(Top.class, members);
    }

    @Test
    void productFamilyVariantsAreProductAndTop() {
        RecordBody record = RecordBody.of(RecordBody.fieldMap());
        ArrayBody array = ArrayBody.of(TypeRef.of("integer"));
        MapBody map = MapBody.of(TypeRef.of("text"), TypeRef.of("text"));
        TupleBody tuple = new TupleBody(List.of());

        for (Top body : List.of(record, array, map, tuple)) {
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

    /** A reference is an atom whose values are names (Part 2 §4.2), as an enum is: a set of names, not a sum. */
    @Test
    void aReferenceIsAnAtom() {
        ReferenceBody reference = new ReferenceBody("uuid_type");
        assertInstanceOf(Atom.class, reference);
        assertInstanceOf(Type.class, reference);
    }

    /**
     * A reference's identity is its {@code of}: its index is derived over a closure, and one reference holds
     * different members in two schemas. A refinement may not name another {@code of}.
     */
    @Test
    void aReferenceIsItsOf() {
        ReferenceBody linked = new ReferenceBody("uuid_type").linked(true, List.of("stock_id", "customer_id"));

        assertEquals(new ReferenceBody("uuid_type"), linked);
        assertEquals(List.of("customer_id", "stock_id"), linked.memberNames());
        assertTrue(linked.namesTypes());
        assertEquals(List.of(), linked.constraintsCheck(new ReferenceBody("uuid_type")));
        assertEquals(1, linked.constraintsCheck(new ReferenceBody("text_type")).size());
    }

    @Test
    void aliasIsTopDirectlyNotThroughAnyBaseKind() {
        // alias => top & { target: type_ref } -- composes with top directly, not through
        // atom/product/sum, so Alias implements Top but is sealed out of all three base kinds
        // (Alias isn't in Atom's/Product's/Sum's own permits lists).
        Alias alias = new Alias(TypeRef.of("token"));
        assertInstanceOf(Top.class, alias);
    }
}
