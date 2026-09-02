package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's three name-hygiene rules over the schema-layer scopes [TSON-SCHEMA] §11.4 names,
 * plus the one this implementation adds -- a template's parameters ({@code SPEC-FEEDBACK.md} #5).
 *
 * <p><b>All three run in one walk</b> ({@code TsonSchemaLinker.checkNames}), which is what makes the scope
 * list the only place a position can be forgotten. The restricted-character rule used to run at the
 * positions that <em>read</em> a name instead -- the schema parser, the definition resolver, the atom
 * vocabulary -- and had holes at exactly the positions only some of those reached: an enum member and a
 * group's member labels were checked for reading alike and for script mixing, and never for a restricted
 * character. Every position below is asserted for all three so that stays true.
 *
 * <p><b>Every confusable pair here is built from code points</b>, never typed. That is not fussiness: the
 * two spellings are indistinguishable in an editor, so a literal would make the test unreviewable and one
 * careless paste away from asserting nothing.
 */
class ConfusableNameScopesTest {

    /** Cyrillic а (U+0430) — the character §9.4 opens with. */
    private static final String CYR_A = new String(Character.toChars(0x0430));

    /**
     * {@code pass} in Cyrillic (р а ѕ ѕ) -- single-script, so the restricted-script rule admits it, and
     * confusable with the Latin spelling, so the look-alike rule is the only one with anything to say.
     */
    private static final String CYRILLIC_PASS =
            new String(new int[] {0x0440, 0x0430, 0x0455, 0x0455}, 0, 4);


    /** Cyrillic А (U+0410), the capital. */
    private static final String CYR_CAP_A = new String(Character.toChars(0x0410));

    private static final String ID = "https://example.test/confusable.tn";

    private static TsonCompiledSchema compile(String declarations) {
        return compileWith(TsonUnicodePolicy.highlyRestrictive(), declarations);
    }

    private static TsonCompiledSchema compileWith(TsonUnicodePolicy identifiers, String declarations) {
        String schema = """
                !!id:"https://example.test/confusable.tn"
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
        return TsonCompiledSchemaRegistry.tree(TsonCompiledMetaRegistry.withStandardLibrary(
                SchemaMetaNameBinder.defaultContext(), source, identifiers)).get(ID);
    }

    private static String refused(String declarations) {
        return assertThrows(TsonSchemaValidationException.class, () -> compile(declarations)).getMessage();
    }

    /** The declared names of one schema — where a spoofed name changes which type a document validates against. */
    @Test
    void twoDeclaredNamesThatReadAlikeAreRefused() {
        String message = refused("  admin => { a: text }\n  " + CYR_A + "dmin => { b: text }");
        assertTrue(message.contains("in the namespace of"), message);
        assertTrue(message.contains("is confusable with 'admin'"), message);
    }

    /** The field names of one record. */
    @Test
    void twoFieldNamesThatReadAlikeAreRefused() {
        assertTrue(refused("  rec => { admin: text  " + CYR_A + "dmin: text }")
                .contains("has field names that read alike"));
    }

    /**
     * <b>A template's parameters, which §11.4 does not list as a scope</b> ({@code SPEC-FEEDBACK.md} #5).
     * {@code <T, Т>} declares two parameters that read identically; a body referencing {@code T} binds one of
     * them and a reviewer cannot see which, which is the substitution hazard §8.2 exists to refuse. §11.4's
     * own reasoning for enum members applies to it unchanged.
     */
    @Test
    void twoTemplateParametersThatReadAlikeAreRefused() {
        String cyrillicCapT = new String(Character.toChars(0x0422));
        assertTrue(refused("  box => <T, " + cyrillicCapT + "> { a: T  b: " + cyrillicCapT + " }")
                .contains("has parameters that read alike"));
    }

    /**
     * §8.2's <b>restricted-character rule</b> at every naming position, including the two that had no check
     * at all while it
     * ran at the reading positions instead: an enum's members and a group's member labels.
     *
     * <p>{@code U+0132} is {@code XID_Continue}, so each name is a well-formed identifier and §7.7 has no
     * complaint -- which is what makes this the policy's to catch and not the grammar's.
     */
    @Test
    void aRestrictedCharacterIsRefusedAtEveryNamingPosition() {
        String bad = "a" + new String(Character.toChars(0x0132)) + "b";
        for (String declaration : new String[] {
                "  " + bad + " => text",
                "  rec => { " + bad + ": text }",
                "  grp => { ( " + bad + ": text | ok: integer ) }",
                "  st => !enum [" + bad + "]",
                "  box => <" + bad + "> { v: " + bad + " }"}) {
            assertTrue(refused(declaration).contains("Identifier_Status=Restricted"), declaration);
        }
    }

    /** And §8.2's <b>restricted-script rule</b> at the position §11.4 omits, which nothing reached before. */
    @Test
    void aMixedScriptParameterNameIsRefused() {
        String mixed = "p" + CYR_A + "y";
        assertTrue(refused("  box => <" + mixed + "> { v: " + mixed + " }")
                .contains("HIGHLY_RESTRICTIVE"));
    }

    /** The members of one enum — distinct strings, so the set's own uniqueness rule cannot see them. */
    @Test
    void twoEnumMembersThatReadAlikeAreRefused() {
        assertTrue(refused("  st => !enum [ACTIVE " + CYR_CAP_A + "CTIVE]")
                .contains("has members that read alike"));
    }

    /**
     * A choice's variants are <b>not</b> a scope of their own, and finding that out is worth a test. A
     * variant is a reference to a declared name, so two confusable variants are two confusable entries in
     * the namespace — caught one level up, before any choice is looked at. §11.4 says so outright; a check
     * over them could never fire.
     */
    @Test
    void confusableChoiceVariantsAreCaughtAsConfusableDeclaredNames() {
        String message = refused("  admin => { a: text }\n  " + CYR_A + "dmin => { b: text }\n"
                + "  either => ( admin | " + CYR_A + "dmin )");
        assertTrue(message.contains("in the namespace of"), message);
    }

    /**
     * The precision that makes the confusable rule a rule rather than a heuristic: it fires on a colliding
     * <em>pair</em>, so no lone name is ever rejected by it.
     *
     * <p>Checked under a relaxed restriction level, because the two rules are independent and the default
     * level does reject these names — which is the whole reason §8.2 keeps them as separate rules rather
     * than one. Here the level is per-segment, so it admits `id_пользователя` and this test is left
     * asserting only what it means to: that the skeleton check stays silent.
     */
    @Test
    void theConfusableRuleNeverFiresOnALoneName() {
        assertNotNull(compileWith(TsonUnicodePolicy.highlyRestrictive().perSegment(),
                "  rec => { id_" + new String(Character.toChars(0x043F))
                        + ": text  url_" + new String(Character.toChars(0x0430)) + ": text }"));
    }

    /** Ordinary schemas are unaffected, which is the property that keeps the rule switched on. */
    @Test
    void anOrdinarySchemaIsUnaffected() {
        assertNotNull(compile("""
                  order    => { id: text  total: text  created_at: text }
                  customer => { id: text  name: text  email_address: text }
                  status   => !enum [OPEN ACTIVE DONE]"""));
    }

    /**
     * Class 1 has no declaration to have caught it, so a schemaless record's own field set is checked where
     * it is read. This is the one scope that needs a reader rather than the linker.
     *
     * <p>The pair is whole-script rather than a within-word homograph, deliberately: a field name is a name
     * (§2.5), so a mixed-script spelling is refused by the restricted-script rule before this one has a pair
     * to compare, and isolating the look-alike rule needs two names each of which is single-script.
     */
    @Test
    void aSchemalessRecordsFieldNamesAreChecked() {
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        new TsonTreeReader().withDiagnostics(problems).read("{ pass: 1  " + CYRILLIC_PASS + ": 2 }");

        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES),
                problems.diagnostics().stream().map(Diagnostic::code).toList(),
                problems.diagnostics().toString());
    }

    /** A duplicate is still the duplicate rule, not this one — the two are different defects. */
    @Test
    void anOutrightDuplicateIsStillReportedAsADuplicate() {
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        new TsonTreeReader().withDiagnostics(problems).read("{ admin: 1  admin: 2 }");

        assertEquals(List.of(Diagnostic.Code.DUPLICATE_FIELD),
                problems.diagnostics().stream().map(Diagnostic::code).toList(),
                problems.diagnostics().toString());
    }
}
