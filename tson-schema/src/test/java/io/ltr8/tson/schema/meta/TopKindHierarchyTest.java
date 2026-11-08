package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link Top}/{@link Atom}/{@link Product}/{@link Sum} hierarchy actually replicates
 * the kernel's own composition chain (`atom => top & {}`, `product => top & { ... }`, `sum => top &
 * {}`, `alias => top & { target: meta.type_ref }`, Part 2 §4.1) as real Java subtyping.
 *
 * <p>There's no need to also assert e.g. {@code !(unit instanceof Product)} -- {@code Product}'s
 * own {@code permits} list doesn't name {@link VoidType} (a {@code final} record), so the compiler
 * already rejects that check as provably impossible at compile time, a stronger guarantee than a
 * runtime assertion would give.
 */
class TopKindHierarchyTest {

    @Test
    void atomFamilyVariantsAreAtomAndTop() {
        assertInstanceOf(Atom.class, new VoidType());
        assertInstanceOf(Top.class, new VoidType());

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
        ReferenceBody reference = ReferenceBody.of("uuid_type");
        assertInstanceOf(Atom.class, reference);
        assertInstanceOf(Type.class, reference);
    }

    /**
     * A reference's identity is its {@code of}: its index is derived over a closure, and one reference holds
     * different members in two schemas. A refinement's {@code of} is the source's or IS-A it, a question the
     * namespace answers ({@link ReferenceBody#ofNarrowing}); a reference refines only a reference.
     */
    @Test
    void aReferenceIsItsOf() {
        ReferenceBody linked = ReferenceBody.of("uuid_type").linked(true, List.of("stock_id", "customer_id"), Map.of(),
                Map.of(), Map.of(), List.of("stock_id", "customer_id"));

        assertEquals(ReferenceBody.of("uuid_type"), linked);
        assertEquals(List.of("customer_id", "stock_id"), linked.memberNames());
        assertTrue(linked.namesTypes());
        assertEquals(List.of(), linked.constraintsCheck(ReferenceBody.of("uuid_type")));
        assertEquals(List.of(), linked.constraintsCheck(ReferenceBody.of("text_type")));
        assertEquals(1, linked.constraintsCheck(new EnumBody(List.of("A"))).size());

        BiPredicate<String, String> isA = (sub, sup) -> sub.equals(sup)
                || sub.equals("uuid_v4_type") && sup.equals("uuid_type");
        assertEquals(List.of(), linked.ofNarrowing(ReferenceBody.of("uuid_type"), isA));
        assertEquals(List.of(), linked.ofNarrowing(ReferenceBody.of("uuid_v4_type"), isA));
        assertEquals(1, linked.ofNarrowing(ReferenceBody.of("text_type"), isA).size());
        assertEquals(1, ReferenceBody.of("uuid_v4_type").ofNarrowing(ReferenceBody.of("uuid_type"), isA).size());
    }

    /**
     * An application resolves by the identity [TSON-SCHEMA] §8.2 gives it: each argument past its alias chain, a
     * nested one first to the entry it closed to. Over {@code user_id => uuid} and {@code uid => user_id}, {@code
     * box<user_id>} is {@code box<uuid>} and {@code box<box<uid>>} is {@code box<box<uuid>>}; a name that is no alias
     * is compared as itself.
     */
    @Test
    void anApplicationIsResolvedPastItsArgumentsAliases() {
        ReferenceBody linked = ReferenceBody.of("type").linked(true, List.of("boxed", "nested"),
                Map.of(box(TypeRef.of("uuid")), "boxed", box(TypeRef.of("boxed")), "nested"),
                Map.of("user_id", "uuid", "uid", "uuid"), Map.of(), List.of());

        assertEquals(Optional.of("boxed"), linked.entryOf(box(TypeRef.of("user_id"))));
        assertEquals(Optional.of("nested"), linked.entryOf(box(box(TypeRef.of("uid")))));
        assertEquals(Optional.empty(), linked.entryOf(box(TypeRef.of("order_id"))));
    }

    private static TypeRef box(TypeRef argument) {
        return TypeRef.of("box", List.of(new TypeArgument.Ref(argument)));
    }

    @Test
    void aliasIsTopDirectlyNotThroughAnyBaseKind() {
        // alias => top & { target: meta.type_ref } -- composes with top directly, not through
        // atom/product/sum, so Alias implements Top but is sealed out of all three base kinds
        // (Alias isn't in Atom's/Product's/Sum's own permits lists).
        Alias alias = new Alias(TypeRef.of("token"));
        assertInstanceOf(Top.class, alias);
    }
}
