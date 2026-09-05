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
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
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

    // ── The other §5.10 declaration-time rules ───────────────────────────
    //    All four are rules a template must satisfy whether or not anyone applies it, and all four were
    //    unchecked: three are one arity rule, and the fourth is the converse of the closed-entry rule.

    /**
     * <b>A template is not a type until it is applied.</b> Naming one without arguments used to compile and
     * link clean, then fail at <em>read</em> time with "no usable compiled reader" and a library-fault exit
     * code -- the author's error reported as this library's, at the latest possible moment. It survived
     * because the eager-rejection discipline guarded *applications* and never bare names.
     */
    @Test
    void namingATemplateWithoutApplyingItIsRejected() {
        String message = rejected("""
                  tmpl => <T> { v: T }
                  use  => { u: tmpl }""");

        assertTrue(message.contains("is a template taking 1 type argument [T]"), message);
        assertTrue(message.contains("not a type until it is applied"), message);
    }

    /**
     * A recursive reference that forgets its arguments is caught <b>when the template is applied</b>, not at
     * its declaration -- and an unapplied one gets no verdict at all.
     *
     * <p><b>The bare-name half of the rule cannot run against a held body.</b> Arity over an *application* is
     * decidable there ({@code chain<T, T>} below), because an application is a distinguishable shape in the
     * wire tree. A bare name is not: a held body's tokens are field names, states, literals and type
     * references alike, so "this token names a template" would reject a schema whose field happens to be
     * called {@code box} beside a template of that name. A false verdict on a correct schema is worse than a
     * late one on an incorrect schema, so the check runs where the shape is unambiguous.
     *
     * <p>An unapplied template getting no verdict is the design's own position, not a shortfall it tolerates
     * -- §5.10: "an unapplied template is checked no further and receives no verdict".
     * `head: T` is not decoration: without it the parameter's only use would have been the missing argument
     * list, and the unused-parameter rule -- which *is* answerable from a held body -- fires first.
     */
    @Test
    void aRecursiveReferenceWithoutArgumentsIsRejectedWhereItIsApplied() {
        assertNotNull(compile("  chain => <T> { head: T  tail: chain? }"),
                "unapplied, it gets no verdict");

        assertTrue(rejected("""
                  chain => <T> { head: T  tail: chain? }
                  use   => { c: chain<text> }""").contains("not a type until it is applied"));
    }

    /**
     * A recursive application's arity is decidable where it is written. {@code TemplateRegularity} cannot
     * report it -- comparing parameters positionally needs the positions to line up -- so it passed through
     * to a materialisation that may never happen.
     */
    @Test
    void aRecursiveApplicationWithTheWrongArityIsRejected() {
        assertTrue(rejected("  chain => <T> { tail: chain<T, T>? }")
                .contains("takes 1 type argument [T], but 2 were applied"));
    }

    /**
     * The converse of §5.10's closed-entry rule: an open entry references every parameter it declares.
     * {@code <T> { v: text }} declares `T` and never uses it, so every application would denote the same
     * type. Deciding this also settles what an open form with no parameter-bearing binding would mean:
     * nothing, because it cannot exist.
     */
    @Test
    void aDeclaredParameterTheBodyNeverReferencesIsRejected() {
        assertTrue(rejected("  tmpl => <T> { v: text }").contains("never references it"));
        assertTrue(rejected("  tmpl => <T, U> { v: T }").contains("parameter 'U'"));
        assertNotNull(compile("  tmpl => <A, B> { x: A  y: B }"), "both used");
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
