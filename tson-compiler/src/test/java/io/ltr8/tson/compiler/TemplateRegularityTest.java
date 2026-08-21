package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.10's regularity boundary ({@code TemplateRegularity}): within a template body, a recursive application
 * -- direct or mutual -- must pass each parameter through unchanged.
 *
 * <p>Checked where the template is <b>declared</b>, which is the point of the rule. A template that grows
 * its argument every level has no finite set of types to build, and catching it only while materialising
 * costs a depth counter -- a non-portable limit, and the retrofit C++ reached for after shipping templates
 * without a regularity restriction. Every fixture here declares the offending template and never applies it.
 */
class TemplateRegularityTest {

    private static final String ID = "https://example.test/regularity.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/regularity.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  box => <T> { v: T }
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static String rejected(String declarations) {
        return assertThrows(TsonSchemaValidationException.class, () -> compile(declarations)).getMessage();
    }

    /** The whole point: rejected at the declaration, with nothing applying it. */
    @Test
    void aGrowingArgumentIsRejectedEvenWhenTheTemplateIsNeverApplied() {
        String message = rejected("  weird => <T> { next: weird<box<T>>? }");

        assertTrue(message.contains("'weird' applies 'weird' recursively"), message);
        assertTrue(message.contains("does not pass 'T' through unchanged"), message);
        assertTrue(message.contains("the application 'box<...>'"), "names what argument 1 actually is: " + message);
    }

    @Test
    void passingTheParameterThroughUnchangedIsRegular() {
        assertNotNull(compile("  chain => <T> { head: T  tail: chain<T>? }"));
    }

    /** A recursive application nested inside another application's arguments is checked too. */
    @Test
    void aGrowingArgumentNestedInsideAnotherApplicationIsCaught() {
        assertTrue(rejected("  deep => <T> { x: box<deep<box<T>>>? }")
                .contains("'deep' applies 'deep' recursively"));
    }

    /** Mutual recursion needs reachability, not a self-edge: neither template applies itself. */
    @Test
    void mutualRecursionIsCheckedAcrossTheCycle() {
        assertNotNull(compile("""
                  ma => <T> { b: mb<T>? }
                  mb => <U> { a: ma<U>? }"""), "regular across the cycle");

        String message = rejected("""
                  na => <T> { b: nb<box<T>>? }
                  nb => <U> { a: na<U>? }""");
        assertTrue(message.contains("'na' applies 'nb' recursively"), message);
    }

    /** An application that is not on a cycle back to its own declaration is left alone. */
    @Test
    void aNonRecursiveApplicationMayGrowItsArgumentFreely() {
        assertNotNull(compile("""
                  holder => <T> { b: box<box<T>>? }"""));
    }

    /**
     * <b>Deliberately stricter than termination requires.</b> Permuting the parameters still reaches
     * finitely many instantiations -- arguments are only ever copied, never constructed -- so
     * {@code swap<B, A>} would terminate. Positional identity is what §5.10's cited precedent uses (ML
     * restricts polymorphic recursion the same way) and an over-restriction that is simple to state can be
     * loosened later, where the reverse cannot. Pinned so the choice is visible rather than incidental.
     */
    @Test
    void permutingParametersIsRejectedThoughItWouldTerminate() {
        assertTrue(rejected("  swap => <A, B> { x: swap<B, A>? }")
                .contains("does not pass 'A' through unchanged"));
    }
}
