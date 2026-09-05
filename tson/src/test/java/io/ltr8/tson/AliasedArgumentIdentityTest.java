package io.ltr8.tson;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Reference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [TSON-SCHEMA] §8.2: an application of a pure rename denotes the same type as an application of the name it
 * renames, so the two are one entry.
 *
 * <p><b>The three ways to name a type after another are three different things</b>, and this is where the
 * difference has to show:
 *
 * <ul>
 *   <li>{@code user_id => uuid} is a <b>reference</b> -- a pure rename. §7.2 compares "after reference
 *       flattening of both", so a {@code user_id} is interchangeable with a {@code uuid} at every position.
 *       {@code box<user_id>} therefore <em>is</em> {@code box<uuid>}, and the resolver dereferences the
 *       argument so that one entry serves both.
 *   <li>{@code user_id => !uuid ^ {}} is a <b>refinement</b>: IS-A {@code uuid}, distinct from its siblings.
 *   <li>{@code user_id => !uuid_type {}} is a <b>fresh type</b>, related to neither.
 * </ul>
 *
 * <p>Only the first is dereferenced. Without that, the model said the arguments were the same type while the
 * applications were not — interchangeable at a scalar position and refused one layer of application up.
 */
class AliasedArgumentIdentityTest {

    private static final String ID = "https://example.test/argument-identity.tn";

    private static TsonLinkedSchema resolve() {
        return Tson.builder().build().resolve("""
                !!id:"%s"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  user_id  => uuid
                  stock_id => uuid
                  chained  => user_id

                  narrowed => !uuid ^ {}
                  fresh    => !uuid_type {}

                  box => <T> { value: T }

                  by_alias   => box<user_id>
                  by_target  => box<uuid>
                  by_sibling => box<stock_id>
                  by_chain   => box<chained>
                  by_narrow  => box<narrowed>
                  by_fresh   => box<fresh>
                }
                """.formatted(ID));
    }

    private static String entryOf(TsonLinkedSchema linked, String alias) {
        return ((Reference) linked.schema().entries().get(alias).body()).target().name();
    }

    /** The rule: an application of a rename is an application of the name it renames. */
    @Test
    void applyingAnAliasAndApplyingItsTargetMintOneEntry() {
        TsonLinkedSchema linked = resolve();
        assertEquals(entryOf(linked, "by_target"), entryOf(linked, "by_alias"));
    }

    /** Two aliases of one type are one type, so their applications are too. */
    @Test
    void twoAliasesOfOneTypeApplyToOneEntry() {
        TsonLinkedSchema linked = resolve();
        assertEquals(entryOf(linked, "by_alias"), entryOf(linked, "by_sibling"));
    }

    /** Transitively -- what is dereferenced is the end of the chain, not one hop of it. */
    @Test
    void aChainOfAliasesAppliesToTheSameEntry() {
        TsonLinkedSchema linked = resolve();
        assertEquals(entryOf(linked, "by_target"), entryOf(linked, "by_chain"));
    }

    /**
     * A refinement is not a rename. {@code narrowed} IS-A {@code uuid} and is not interchangeable with it, so
     * an application of it is its own type.
     */
    @Test
    void applyingARefinementMintsItsOwnEntry() {
        TsonLinkedSchema linked = resolve();
        assertNotEquals(entryOf(linked, "by_target"), entryOf(linked, "by_narrow"));
    }

    /** And a fresh instance of the constructor is related to neither, so neither is its application. */
    @Test
    void applyingAFreshTypeMintsItsOwnEntry() {
        TsonLinkedSchema linked = resolve();
        assertNotEquals(entryOf(linked, "by_target"), entryOf(linked, "by_fresh"));
        assertNotEquals(entryOf(linked, "by_narrow"), entryOf(linked, "by_fresh"));
    }
}
