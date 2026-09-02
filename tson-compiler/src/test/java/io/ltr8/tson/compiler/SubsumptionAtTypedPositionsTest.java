package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §7.2: "at a position whose declared type is {@code T}, a value annotated {@code !S} is valid
 * if and only if, after reference flattening of both (§8.3), {@code S} is {@code T} or {@code T} appears in
 * {@code S}'s transitive {@code type_definition.supertypes}".
 *
 * <p>The rule was enforced only where {@code T} was a record with a non-empty {@code subtypes()} -- the one
 * case that got a dispatcher wrapped around it (issue #235). Everywhere else the type-ref was consumed and
 * discarded, so a document could claim any type at any atom, array, map or tuple position. The positive path
 * always worked, which is why it went unnoticed: the missing half was only the refusal.
 */
class SubsumptionAtTypedPositionsTest {

    private static final String ID = "https://example.test/sub.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/sub.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledSchemaRegistry.tree(
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source))
                .get(ID);
    }

    private static final String SCHEMA = """
              person   => { name: text }
              employee => person & { badge: text }
              holder   => { t: text  r: person  a: [text]  m: {text => text} }""";

    private static Object read(String document) {
        return compile(SCHEMA).get("holder").read(TestDocuments.document(document));
    }

    private static String refused(String document) {
        return assertThrows(TsonReadException.class, () -> read(document)).getMessage();
    }

    private static final String REST = "  r: { name: \"n\" }  a: [ \"x\" ]  m: { \"k\" => \"v\" }";

    /** An atom position: the case that reported this, and the one no dispatcher ever guarded. */
    @Test
    void anUnrelatedTypeIsRefusedAtAnAtomPosition() {
        // `text` does carry a subtype here (core's `non_empty_text`), so this takes the "not a known
        // subtype" wording -- the point is that an atom position now refuses at all, which it never did.
        assertTrue(refused("{ t: !uuid \"x\" " + REST + " }").contains("'uuid' is not a known subtype of 'text'"));
        assertTrue(refused("{ t: !nosuch \"x\" " + REST + " }").contains("'nosuch'"));
    }

    /** Array, map and tuple positions consumed the type-ref and discarded it in the same way. */
    @Test
    void anUnrelatedTypeIsRefusedAtContainerPositions() {
        assertTrue(refused("{ t: \"x\"  r: { name: \"n\" }  a: !nosuch [ \"x\" ]  m: { \"k\" => \"v\" } }")
                .contains("'nosuch'"));
        assertTrue(refused("{ t: \"x\"  r: { name: \"n\" }  a: [ \"x\" ]  m: !nosuch { \"k\" => \"v\" } }")
                .contains("'nosuch'"));
    }

    /** And at a record whose type simply has no subtype -- the gap was never about atoms. */
    @Test
    void anUnrelatedTypeIsRefusedAtARecordPositionWithNoSubtypes() {
        TsonCompiledSchema compiled = compile("  base => { name: text }\n  h => { f: base }");
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("h").read(TestDocuments.document("{ f: !nosuch { name: \"x\" } }")))
                .getMessage().contains("'nosuch' is not valid at a 'base' position"));
    }

    /** The positive path, which always worked and must keep working: a declared subtype is admitted... */
    @Test
    void aDeclaredSubtypeIsAdmittedAndValidatedAsItself() {
        assertNotNull(read("{ t: \"x\"  r: !employee { name: \"n\"  badge: \"b\" }  a: [ \"x\" ]"
                + "  m: { \"k\" => \"v\" } }"));
    }

    /** ...and its own fields are then what is checked, not the position's. */
    @Test
    void anUnannotatedValueIsStillValidatedAsThePositionsOwnType() {
        assertTrue(refused("{ t: \"x\"  r: { name: \"n\"  badge: \"b\" }  a: [ \"x\" ]  m: { \"k\" => \"v\" } }")
                .contains("badge"));
    }

    /** Naming the position's own type is always valid -- §7.2's "S is T". */
    @Test
    void thePositionsOwnTypeIsAlwaysAdmitted() {
        assertNotNull(read("{ t: !text \"x\"  r: !person { name: \"n\" }  a: [ \"x\" ]  m: { \"k\" => \"v\" } }"));
    }

    /**
     * §7.2 compares "after reference flattening of <b>both</b>", and §8.3 makes an alias and its target one
     * type -- so an alias names the position's own type. The reader running at such a position belongs to
     * the target, so this is the case a name-only comparison gets wrong.
     */
    @Test
    void anAliasOfThePositionsTypeIsAdmitted() {
        TsonCompiledSchema compiled = compile("""
                  base  => { name: text }
                  other => base
                  h     => { f: other }""");

        assertNotNull(compiled.get("h").read(TestDocuments.document("{ f: !other { name: \"x\" } }")));
        assertNotNull(compiled.get("h").read(TestDocuments.document("{ f: !base { name: \"x\" } }")));
    }

    /** A choice keeps its own membership relation (§5.4), which subsumption must not override. */
    @Test
    void aChoiceStillDispatchesOnItsVariants() {
        TsonCompiledSchema compiled = compile("""
                  mail_addr => { address: text }
                  phone_no  => { number: text }
                  contact => ( mail_addr | phone_no )
                  h => { f: contact }""");

        assertNotNull(compiled.get("h").read(TestDocuments.document("{ f: !mail_addr { address: \"a\" } }")));
    }
}
