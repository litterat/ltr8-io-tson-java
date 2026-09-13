package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §7.2 compares a written type annotation against a position's type "after reference flattening
 * of <b>both</b>". So an alias and its target are one type at either end of that comparison: {@code !b_of} at
 * a {@code base} position names the position's own type, and {@code !s_of} names a subtype, whichever of the
 * two names the schema happens to have flattened.
 *
 * <p><b>The reason this is not a nicety.</b> A materialised entry's own name is implementation-chosen and
 * non-normative (§8.2), so for a template instantiation an alias is the only name an author has. Without
 * flattening on the subtype side, a subtype family over instantiations is unwritable: the family exists in
 * the index and nothing can name a member of it.
 *
 * <p><b>The alias is admitted, not rewritten to its target.</b> A reference entry compiles to its target's
 * reader named for the entry doing the referring, so dispatching on the written name runs the same reader and
 * reports under the name the author typed.
 */
class AliasAtASubsumptionPositionTest {

    private static final String ID = "https://example.test/alias-subsumption.tn";

    private record Read(List<Diagnostic> problems) {

        void accepted() {
            assertEquals(List.of(), problems.stream().map(Diagnostic::message).toList(), "expected a clean read");
        }

        Diagnostic refusal() {
            assertEquals(1, problems.size(), () -> "expected exactly one problem, got " + problems);
            return problems.getFirst();
        }
    }

    private static Read read(String declarations, String rootType, String document) {
        String schema = """
                !!id:"https://example.test/alias-subsumption.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        TsonCompiledSchemaRegistry registry = TsonCompiledSchemaRegistry.tree(core);
        registry.get(ID);
        List<Diagnostic> problems = new ArrayList<>();
        new TsonTreeReader(registry).withDiagnostics(problems::add).withSchema(ID).readAs(document, rootType);
        return new Read(List.copyOf(problems));
    }

    private static final String LATTICE = """
              base   => { tag: text }
              sub    => base & { extra: text }
              other  => base & { more: text }
              s_of   => sub
              b_of   => base
              holder => { r: base }
            """;

    @Test
    void anAliasOfASubtypeIsAdmitted() {
        read(LATTICE, "holder", """
                { r: !s_of { tag: "x"  extra: "y" } }
                """).accepted();
    }

    /**
     * The position's own type, where that type has subtypes. This is the case that used to turn on which
     * construction site the reader came from: a record with no subtypes was wrapped by the subsumption guard,
     * which flattened aliases, and one with subtypes was wrapped by its own factory, which did not.
     */
    @Test
    void anAliasOfThePositionsOwnTypeIsAdmittedWhereThatTypeHasSubtypes() {
        read(LATTICE, "holder", """
                { r: !b_of { tag: "x" } }
                """).accepted();
    }

    /** The same, where the type has none -- the branch that always worked, kept so the pair cannot drift. */
    @Test
    void anAliasOfThePositionsOwnTypeIsAdmittedWhereThatTypeHasNoSubtypes() {
        read("""
                  leaf   => { tag: text }
                  l_of   => leaf
                  holder => { r: leaf }
                """, "holder", """
                { r: !l_of { tag: "x" } }
                """).accepted();
    }

    /** The alias is dispatched to, so the subtype's own fields are validated rather than the base's. */
    @Test
    void theAliasReachesTheSubtypesOwnReader() {
        Diagnostic refused = read(LATTICE, "holder", """
                { r: !s_of { tag: "x" } }
                """).refusal();

        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refused.code());
        assertTrue(refused.message().contains("extra"),
                () -> "sub's own required field, not base's field set: " + refused.message());
    }

    /** An alias of a sibling is refused like the sibling: flattening decides what a name means, not whether. */
    @Test
    void anAliasOfSomethingUnrelatedIsStillRefused() {
        Diagnostic refused = read("""
                  base    => { tag: text }
                  sub     => base & { extra: text }
                  apart   => { only: text }
                  a_of    => apart
                  holder  => { r: base }
                """, "holder", """
                { r: !a_of { only: "x" } }
                """).refusal();

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
    }

    // ── The two family dispatchers (§5.2) ───────────────────────────────

    /**
     * A <b>sealed</b> family selects its member by reading the value's own discriminator, and a tag there may
     * only agree (§5.2). "Agree" is a comparison of names, so it flattens like every other: an alias of the
     * selected member says what the members already said.
     */
    private static final String SEALED = """
              pet    => @sealed { @discriminator kind: text  name: text }
              dog    => pet & { kind: = "dog"  breed: text }
              d_of   => dog
              p_of   => pet
              kennel => { p: pet }
            """;

    @Test
    void anAliasOfTheSelectedMemberAgreesWithASealedFamilysDiscriminator() {
        read(SEALED, "kennel", """
                { p: !d_of { kind: "dog"  name: "Rex"  breed: "lab" } }
                """).accepted();
    }

    /**
     * And an alias of the sealed <em>base</em> selects nothing, which is the base's own rule reached through
     * a second name. Without flattening it fell past that rule into the member dispatch and was refused for
     * contradicting a discriminator it never disagreed with -- the wrong verdict, with the wrong remedy.
     */
    @Test
    void anAliasOfASealedBaseSelectsNothing() {
        Diagnostic refused = read(SEALED, "kennel", """
                { p: !p_of { kind: "dog"  name: "Rex"  breed: "lab" } }
                """).refusal();

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertTrue(refused.message().contains("selects nothing"), refused.message());
    }

    /**
     * The case the rule exists for: a subtype template's instantiation, whose entry name the resolver minted
     * and no author can write. The alias is the only name there is.
     */
    @Test
    void anAliasIsHowATemplateInstantiationIsNamedAtAll() {
        read("""
                  result    => <T> { payload: T }
                  ok        => <T> result<T> & { note: text }
                  result_of => result<text>
                  ok_of     => ok<text>
                  holder    => { r: result_of }
                """, "holder", """
                { r: !ok_of { payload: "x"  note: "y" } }
                """).accepted();
    }
}
