package io.ltr8.tson;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [TSON-SCHEMA] §8.2: an application of a pure rename denotes the same type as an application of the name it
 * renames, so the two record the same <b>canonical application</b>.
 *
 * <p><b>The three ways to name a type after another are three different things</b>, and this is where the
 * difference has to show:
 *
 * <ul>
 *   <li>{@code user_id => uuid} is a <b>reference</b> -- a pure rename. §7.2 compares "after reference
 *       flattening of both", so a {@code user_id} is interchangeable with a {@code uuid} at every position.
 *       {@code box<user_id>} therefore <em>is</em> {@code box<uuid>}, and the resolver dereferences the
 *       argument so that both applications canonicalise the same way.
 *   <li>{@code user_id => !uuid ^ {}} is a <b>refinement</b>: IS-A {@code uuid}, distinct from its siblings.
 *   <li>{@code user_id => !uuid_type {}} is a <b>fresh type</b>, related to neither.
 * </ul>
 *
 * <p>Only the first is dereferenced. Without that, the model said the arguments were the same type while the
 * applications were not -- interchangeable at a scalar position and refused one layer of application up.
 *
 * <p><b>What this test no longer asks is "one entry".</b> Each of these declarations is its own entry
 * (§8.2) -- two declarations naming one application are two entries with one
 * structure, as two hand-written records with the same fields are -- so the comparison is the application
 * each records, which is what §8.2 keys identity on and where the dereferencing shows.
 */
class AliasedArgumentIdentityTest {

    private static final String ID = "https://example.test/argument-identity.tn";

    private static TsonLinkedSchema resolve() {
        return Tson.standard().resolve("""
                !!id:"%s"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
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

    /**
     * <b>The canonical application a declaration records</b>, which is what §8.2 keys identity on -- the
     * arguments with every reference chain followed to its terminal entry.
     *
     * <p>Each of these declarations is now its own entry (§8.2), so "one entry" is no
     * longer the question a rename test can ask: two declarations naming one application are two entries with
     * one structure, exactly as two hand-written records with the same fields are. What survives, and is the
     * property §8.2 actually states, is that the two record the <em>same application</em>: dereferencing
     * {@code user_id} to {@code uuid} happens in {@code source}, so {@code box<user_id>} and
     * {@code box<uuid>} agree there even though each declaration keeps its own name.
     */
    private static String applicationOf(TsonLinkedSchema linked, String alias) {
        return linked.schema().entries().get(alias).source().orElseThrow().toString();
    }

    /** The rule: an application of a rename is an application of the name it renames. */
    @Test
    void applyingAnAliasAndApplyingItsTargetRecordOneApplication() {
        TsonLinkedSchema linked = resolve();
        assertEquals(applicationOf(linked, "by_target"), applicationOf(linked, "by_alias"));
    }

    /** Two aliases of one type are one type, so their applications are too. */
    @Test
    void twoAliasesOfOneTypeRecordOneApplication() {
        TsonLinkedSchema linked = resolve();
        assertEquals(applicationOf(linked, "by_alias"), applicationOf(linked, "by_sibling"));
    }

    /** Transitively -- what is dereferenced is the end of the chain, not one hop of it. */
    @Test
    void aChainOfAliasesRecordsTheSameApplication() {
        TsonLinkedSchema linked = resolve();
        assertEquals(applicationOf(linked, "by_target"), applicationOf(linked, "by_chain"));
    }

    /**
     * A refinement is not a rename. {@code narrowed} IS-A {@code uuid} and is not interchangeable with it, so
     * an application of it is its own type.
     */
    @Test
    void applyingARefinementRecordsItsOwnApplication() {
        TsonLinkedSchema linked = resolve();
        assertNotEquals(applicationOf(linked, "by_target"), applicationOf(linked, "by_narrow"));
    }

    /** And a fresh instance of the constructor is related to neither, so neither is its application. */
    @Test
    void applyingAFreshTypeRecordsItsOwnApplication() {
        TsonLinkedSchema linked = resolve();
        assertNotEquals(applicationOf(linked, "by_target"), applicationOf(linked, "by_fresh"));
        assertNotEquals(applicationOf(linked, "by_narrow"), applicationOf(linked, "by_fresh"));
    }
}
