package io.ltr8.tson;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.ScriptPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [TSON-DATA] §8.2's name hygiene at a value whose type is an identifier family. Each such value meets the two
 * per-name rules under the read's identifier policy and its family's own profile, and the keys of a map whose key
 * type is one, and the elements of a set of one, are a naming scope ([TSON-SCHEMA] §11.4), so no two may read alike.
 *
 * <p>The scope is isolated by a pair of names that are each single-script -- Latin {@code pass} beside Cyrillic
 * {@code раѕѕ} -- since a within-word homograph such as {@code аdmin} is refused as a restricted script before the
 * look-alike rule has a pair to compare (§8.2). Confusable characters are written as escapes, never literally.
 */
class IdentifierValueHygieneTest {

    /** {@code аdmin}, its first letter U+0430: a mixed-script name. */
    private static final String MIXED = "аdmin";

    /** {@code раѕѕ} in Cyrillic: single-script, and read alike with Latin {@code pass}. */
    private static final String CYRILLIC_PASS = "раѕѕ";

    private static final String ID = "https://example.test/names-2.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/names-2.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              identifier => !identifier_type { continue_add: "-" }
              js_name => !identifier_type { start: ID  continue: ID  start_add: "$_"  continue_add: "$" }
              handlers => { identifier => text }
              bindings => { js_name => text }
              labels => { text => text }
              route => { name: identifier }
              registry => { handlers: { identifier => text } }
              team => { roles: set<identifier> }
              ranked => !array { element_type: identifier  unique_items: true }
              lists => { path: [identifier]  tags: set<text>  counts: set<int32> }
            }
            """;

    @Typename(name = "registry")
    public record Registry(Map<String, String> handlers) {
    }

    @Typename(name = "team")
    public record Team(Set<String> roles) {
    }

    private static Tson tson(IdentifierPolicy identifiers) {
        SchemaSource source = uri -> {
            if (uri.equals(ID)) {
                return SCHEMA;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                    "this fixture serves only " + ID, null);
        };
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withIdentifierPolicy(identifiers)
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("registry", Registry.class, "team", Team.class))
                                .orElse(SchemaMetaNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));
    }

    private static List<Diagnostic> validate(String body) {
        return validate(IdentifierPolicy.defaults(), body);
    }

    private static List<Diagnostic> validate(IdentifierPolicy identifiers, String body) {
        return tson(identifiers).validate("!!schema:\"" + ID + "\"\n" + body);
    }

    /** The one diagnostic {@code body} draws, asserting it is the only one. */
    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    @Test
    void aMixedScriptKeyIsRefusedAsAName() {
        Diagnostic refused = only(validate("!handlers { admin => a  " + MIXED + " => b }"));
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refused.code());
        assertEquals(Optional.of("/" + MIXED), refused.path());

        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, only(validate("!handlers { " + MIXED + " => b }")).code());
    }

    @Test
    void twoKeysThatReadAlikeAreRefusedAtTheSecond() {
        Diagnostic refused = only(validate("!handlers { pass => a  " + CYRILLIC_PASS + " => b }"));
        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES, refused.code());
        assertEquals(Optional.of("/" + CYRILLIC_PASS), refused.path());
    }

    /** A repeat is the duplicate it is, and not also a pair that reads alike. */
    @Test
    void aRepeatedKeyIsADuplicateAndNothingElse() {
        assertEquals(Diagnostic.Code.DUPLICATE_MAP_KEY, only(validate("!handlers { pass => a  pass => b }")).code());
    }

    /** A key typed {@code text} is a value, which carries whatever its domain does. */
    @Test
    void aTextKeyedMapIsNotAScope() {
        assertEquals(List.of(), validate("!labels { pass => a  " + CYRILLIC_PASS + " => b  " + MIXED + " => c }"));
    }

    @Test
    void anIdentifierTypedFieldValueIsAName() {
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, only(validate("!route { name: " + MIXED + " }")).code());
        assertEquals(List.of(), validate("!route { name: admin }"));
    }

    /**
     * A character a profile adds is the profile's own and meets no restricted-character rule, as §7.7's {@code -}
     * is the kernel profile's; a restricted character the profile does not add -- U+0132, a ligature that is
     * {@code XID_Continue} and {@code Identifier_Status=Restricted} -- is still refused.
     */
    @Test
    void aCharacterTheProfileAddsIsNotRestricted() {
        assertEquals(List.of(), validate("!bindings { \"$scope\" => a  \"_private\" => b }"));
        assertEquals(Diagnostic.Code.RESTRICTED_CHARACTER,
                only(validate("!handlers { a\u0132b => a }")).code());
    }

    /**
     * A per-segment level divides a value at its own family's separators: {@code $} is {@code js_name}'s, so a
     * Latin word and a Cyrillic one either side of it are two segments, while a homograph inside one word is
     * still refused.
     */
    @Test
    void aPerSegmentPolicyDividesAtTheFamilysOwnSeparators() {
        IdentifierPolicy perSegment = IdentifierPolicy.defaults().perSegment();
        String compound = "\"id$\u043F\u0443\u0442\u044C\"";
        assertEquals(List.of(), validate(perSegment, "!bindings { " + compound + " => a }"));
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, only(validate("!bindings { " + compound + " => a }")).code());
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT,
                only(validate(perSegment, "!bindings { \"id$" + MIXED + "\" => a }")).code());
    }

    /** §8.2's relaxation is the deployment's, stated in code, and reaches these values as it reaches names. */
    @Test
    void aRelaxedPolicyAdmitsWhatItRelaxes() {
        assertEquals(List.of(), validate(IdentifierPolicy.of(ScriptPolicy.unrestricted()),
                "!handlers { " + MIXED + " => b }"));
    }

    /**
     * A value the schema supplies is a name as the same token in a document is: a default reaches every document
     * that omits the field, so the reader must not inject what it would refuse if written. Judged when the schema
     * links, under the family's own profile.
     */
    @Test
    void aSchemasOwnIdentifierTypedValueIsAName() {
        List<Diagnostic> refused = tson(IdentifierPolicy.defaults()).validateSchema(schemaWith(
                "account => { role?: identifier ~ " + MIXED + " }"));
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, only(refused).code());
        assertEquals(Diagnostic.Code.RESTRICTED_CHARACTER, only(tson(IdentifierPolicy.defaults())
                .validateSchema(schemaWith("account => { kind: identifier = a\u0132b }"))).code());

        assertEquals(List.of(), tson(IdentifierPolicy.defaults()).validateSchema(schemaWith(
                "account => { role?: identifier ~ admin  bind?: js_name ~ \"$scope\"  tag?: text ~ " + MIXED + " }")));
    }

    private static String schemaWith(String declaration) {
        return """
                !!id:"https://example.test/names-3.tn"
                !!meta:"https://tson.io/2026/37/m/meta.tn"
                !!import:"https://tson.io/2026/37/m/core.tn"
                {
                  identifier => !identifier_type { continue_add: "-" }
                  js_name => !identifier_type { start: ID  continue: ID  start_add: "$_"  continue_add: "$" }
                  %s
                }
                """.formatted(declaration);
    }

    /** Object binding reads the same map through its own reader, and refuses the same keys. */
    @Test
    void bindingRefusesTheSameKeys() {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        Tson tson = tson(IdentifierPolicy.defaults());
        tson.objectReader().withDiagnostics(problems).read("!!schema:\"" + ID + "\"\n!registry { handlers: { pass => a  "
                + CYRILLIC_PASS + " => b } }", Registry.class);
        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES, only(problems.diagnostics()).code());
    }

    /** A set of names is one scope, as an identifier-keyed map's keys are; the pair is refused at the second. */
    @Test
    void twoSetElementsThatReadAlikeAreRefusedAtTheSecond() {
        Diagnostic refused = only(validate("!team { roles: [pass " + CYRILLIC_PASS + "] }"));
        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES, refused.code());
        assertEquals(Optional.of("/roles/1"), refused.path());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, only(validate("!team { roles: [pass pass] }")).code(),
                "a repeat is the duplicate it is, and not also a pair that reads alike");
    }

    /** Uniqueness makes the scope, not being a set: an ordered array whose elements are unique is one too. */
    @Test
    void anOrderedArrayOfUniqueNamesIsAScopeToo() {
        Diagnostic refused = only(validate("!ranked [pass " + CYRILLIC_PASS + "]"));
        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES, refused.code());
        assertEquals(Optional.of("/1"), refused.path());
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, only(validate("!ranked [admin " + MIXED + "]")).code());
    }

    /** Repetition is admitted in an array, so it claims nothing about its elements; a set of text holds values. */
    @Test
    void anArrayOfNamesAndASetOfTextAreNotScopes() {
        assertEquals(List.of(), validate("!lists { path: [pass " + CYRILLIC_PASS + "]  tags: [pass " + CYRILLIC_PASS
                + "]  counts: [] }"));
    }

    /** Object binding reads the set through its own reader, and refuses the same pair. */
    @Test
    void bindingRefusesTheSameElements() {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        tson(IdentifierPolicy.defaults()).objectReader().withDiagnostics(problems)
                .read("!!schema:\"" + ID + "\"\n!team { roles: [pass " + CYRILLIC_PASS + "] }", Team.class);
        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES, only(problems.diagnostics()).code());
    }

    /**
     * An element that fails to read is reported as itself and leaves the set's duplicate check alone: it has no
     * value to compare. Whether it failed its form or was refused as a name, in tree and bind modes alike.
     */
    @Test
    void aSetElementThatFailsIsReportedAndNothingElse() {
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID,
                only(validate("!lists { path: []  tags: []  counts: [1 x 2] }")).code());
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, only(validate("!team { roles: [admin " + MIXED + "] }")).code());

        DiagnosticsCollector problems = new DiagnosticsCollector();
        tson(IdentifierPolicy.defaults()).objectReader().withDiagnostics(problems)
                .read("!!schema:\"" + ID + "\"\n!team { roles: [admin " + MIXED + "] }", Team.class);
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, only(problems.diagnostics()).code());
    }
}
