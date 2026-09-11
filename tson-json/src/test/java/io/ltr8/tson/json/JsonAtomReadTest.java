package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §5: an atom-typed position, read from the JSON kinds its family admits.
 *
 * <p>The two halves under test are the two the design splits: <b>which JSON kinds reach the parser</b>, which
 * is this encoding's own ({@code AtomForm}), and <b>what the parser then does with the content</b>, which
 * is the shared vocabulary's and must not differ from the TSON text reading of the same content (§5.1).
 */
class JsonAtomReadTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/atoms-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              count      => int32
              exact      => number
              ratio      => float64
              label      => text
              key        => uuid
              day        => date
              blob       => bytes
              colour     => !enum [ RED GREEN BLUE ]
              flag       => boolean
              nothing    => void
              small      => !integer ^ { min: 0  max: 10 }
              alias      => count
              point      => { x: int32  y: int32 }
              lookup     => { text => int32 }
            }
            """;

    private static final Tson TSON = Tson.standard();

    /**
     * Compiled over §5's vocabulary alone, with no read mode over it -- so these tests can assert what each
     * family's parser <em>produced</em>. Tree mode discards that by design (it answers "does this conform"
     * and hands back the JSON), which is exactly why the parsing contract is pinned here instead.
     */
    private static final JsonCompiledSchema COMPILED =
            JsonSchemaCompiler.compile(TSON.resolve(SCHEMA), ValueReaderFactoryRegistry.atoms());

    /**
     * The kernel's own compiled readers. {@code value} and {@code identifier} are declared by meta-kernel.tn
     * and by nothing else -- core.tn re-declares only {@code void} -- so the two {@code unit} instances this
     * encoding reads by rules of its own (§5.7) are reachable only here.
     */
    private static final JsonCompiledSchema KERNEL = JsonSchemaCompiler.compile(
            TSON.schemaRegistry().get(TsonBundledSchemas.META_KERNEL_ID)
                    .orElseThrow(() -> new IllegalStateException("meta-kernel.tn is not registered")),
            ValueReaderFactoryRegistry.atoms());

    /** One value read at {@code typeName}, with every problem collected rather than thrown. */
    private static Read read(String typeName, String json) {
        return read(COMPILED, typeName, json);
    }

    /**
     * Drives one compiled reader the way a facade will: the root declaration is <b>seeded from the name the
     * read entered through</b>, not left to the reader to claim. The two differ whenever that name aliases
     * something else -- {@code day => date} compiles to core.tn's {@code date} reader, and a diagnostic
     * naming core.tn points the author at a file they did not write instead of the line they can edit.
     */
    private static Read read(JsonCompiledSchema schema, String typeName, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonStream events = new JsonStream(bytes, ProcessorPolicy.defaults(), receiver);
            JsonReadContext ctx = JsonReadContext.of(events, receiver);
            ctx = schema.rootDeclaration(typeName).map(ctx::underDeclaration).orElse(ctx);
            Object value = schema.get(typeName).read(ctx);
            return new Read(value, List.copyOf(problems));
        }
    }

    private record Read(Object value, List<Diagnostic> problems) {

        Object accepted() {
            assertEquals(List.of(), problems.stream().map(Diagnostic::message).toList(),
                    "expected a clean read");
            return value;
        }

        Diagnostic refusal() {
            assertEquals(1, problems.size(), () -> "expected exactly one problem, got " + problems);
            return problems.getFirst();
        }
    }

    // ── §5.3 the exact tier ──────────────────────────────────────────────

    @Test
    void anIntegerPositionTakesAJsonNumberWithNoFractionOrExponent() {
        assertEquals(42, read("count", "42").accepted());
    }

    /**
     * §5.3: "one with either part is not an integer -- {@code 1.0} at an {@code integer} position is a
     * resolver error (contract rejection), exactly as the token {@code 1.0} is in text". So the refusal is
     * the family's, and it is the form code rather than the constraint one.
     */
    @Test
    void anIntegerPositionRefusesAFractionalNumber() {
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, read("count", "1.0").refusal().code());
    }

    /** §5.3: a {@code number} keeps its digits and its scale -- {@code 199.90} is not {@code 199.9}. */
    @Test
    void anExactNumberKeepsItsDigitsAndScale() {
        assertEquals(new BigDecimal("199.90"), read("exact", "199.90").accepted());
    }

    /** §3.1: precision is arbitrary and preserved, and an implementation that cannot hold the digits errors. */
    @Test
    void anExactNumberKeepsMoreDigitsThanABinary64Would() {
        assertEquals(new BigDecimal("9007199254740993"), read("exact", "9007199254740993").accepted());
    }

    // ── §5.4 the approximate tier ────────────────────────────────────────

    @Test
    void anApproximatePositionTakesAJsonNumber() {
        assertEquals(1.5d, read("ratio", "1.5").accepted());
    }

    /**
     * §5.4: the specials have no JSON number spelling and arrive as strings holding [TSON-DATA] §7.6's own
     * productions -- deliberately the TSON spellings, "so that the two encodings share one grammar and one
     * parser for the same content".
     */
    @Test
    void anApproximatePositionTakesTheSpecialValuesAsStrings() {
        assertEquals(Double.POSITIVE_INFINITY, read("ratio", "\".inf\"").accepted());
        assertEquals(Double.NEGATIVE_INFINITY, read("ratio", "\"-.inf\"").accepted());
        assertTrue(((Double) read("ratio", "\".nan\"").accepted()).isNaN());
    }

    /** The IEEE-flavoured spellings are not admitted: no third spelling of infinity enters the series. */
    @Test
    void anApproximatePositionRefusesTheIeeeSpellings() {
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, read("ratio", "\"Infinity\"").refusal().code());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, read("ratio", "\"NaN\"").refusal().code());
    }

    // ── §5.6 string-content families ─────────────────────────────────────

    @Test
    void aStringContentFamilyFacesItsOwnParser() {
        assertEquals("hello", read("label", "\"hello\"").accepted());
        assertEquals(UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6"),
                read("key", "\"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\"").accepted());
        assertEquals(LocalDate.of(2026, 7, 1), read("day", "\"2026-07-01\"").accepted());
    }

    /** §5.6: {@code bytes} is an octet sequence, spelled in the alphabet the type's {@code encoding} names. */
    @Test
    void bytesReadsItsSelectedAlphabet() {
        assertInstanceOf(byte[].class, read("blob", "\"AQID\"").accepted());
        assertEquals(3, ((byte[]) read("blob", "\"AQID\"").accepted()).length);
    }

    /**
     * §5.1's boundary rule: the string's content faces the same parser and the same error split as the
     * equivalent TSON token, so a malformed date is the family's contract rejection and not a JSON problem.
     */
    @Test
    void aMalformedStringIsTheFamilysContractRejection() {
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, read("day", "\"2026-13-99\"").refusal().code());
    }

    /** §5: a kind the family does not admit is a validation error -- value of the wrong form. */
    @Test
    void aFamilyRefusesAKindItDoesNotAdmit() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("label", "42").refusal().code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("count", "\"42\"").refusal().code());
    }

    /** Constraint checking is unchanged from [TSON-SCHEMA] §7.4 and knows nothing of the encoding. */
    @Test
    void aConstraintViolationIsSeparatedFromAContractRejection() {
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, read("small", "11").refusal().code());
    }

    // ── §5.2 booleans and enums ──────────────────────────────────────────

    /** §5.2: an enum member's JSON form is the form of its lexical class -- an identifier member is a string. */
    @Test
    void anEnumMemberArrivesAsAStringOfItsNfcText() {
        assertEquals("GREEN", read("colour", "\"GREEN\"").accepted());
    }

    @Test
    void anEnumRefusesAValueMatchingNoMember() {
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, read("colour", "\"MAUVE\"").refusal().code());
    }

    /** {@code boolean} is that general rule applied, not an exception to it -- and it reads a real Boolean. */
    @Test
    void booleanReadsAJsonBooleanToABoolean() {
        assertEquals(Boolean.TRUE, read("flag", "true").accepted());
        assertEquals(Boolean.FALSE, read("flag", "false").accepted());
    }

    @Test
    void booleanRefusesTheStringSpellingOfItsMembers() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("flag", "\"true\"").refusal().code());
    }

    // ── §5.7 void and value ──────────────────────────────────────────────

    /** §5.7: at a {@code void} position the absent sentinel is the only conforming value, and null spells it. */
    @Test
    void voidTakesNullAndNothingElse() {
        assertNull(read("nothing", "null").accepted());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("nothing", "0").refusal().code());
    }

    /**
     * §5.7: {@code value} classifies by JSON's own grammar and invokes none of [TSON-DATA] §4's base type
     * resolution -- a number without fraction or exponent is an integer, with either a float, and a string's
     * content is uninspected, so {@code "null"} is the four-character string.
     */
    @Test
    void theValueEscapeHatchClassifiesByJsonsOwnGrammar() {
        assertEquals(Boolean.TRUE, read(KERNEL, "value", "true").accepted());
        assertEquals(new BigInteger("7"), read(KERNEL, "value", "7").accepted());
        assertEquals(new BigDecimal("7.5"), read(KERNEL, "value", "7.5").accepted());
        assertEquals("null", read(KERNEL, "value", "\"null\"").accepted());
    }

    /** §5.7: {@code value} admits scalars, so an object or array there is a validation error. */
    @Test
    void theValueEscapeHatchRefusesAContainer() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read(KERNEL, "value", "{\"a\": 1}").refusal().code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read(KERNEL, "value", "[1]").refusal().code());
    }

    /** {@code identifier} is the third {@code unit} instance, and the one the shared vocabulary does answer for. */
    @Test
    void identifierFacesTheSharedNameProfile() {
        assertEquals("order_id", read(KERNEL, "identifier", "\"order_id\"").accepted());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID,
                read(KERNEL, "identifier", "\"42nd\"").refusal().code());
    }

    /**
     * §7: JSON null is the absent sentinel, spent before any family rule applies -- so at every REQUIRED
     * atom position it is a validation error, precisely as {@code _} is in text.
     */
    @Test
    void nullAtAnAtomPositionIsARefusalRatherThanAValue() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("label", "null").refusal().code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("count", "null").refusal().code());
    }

    // ── The compile ──────────────────────────────────────────────────────

    /**
     * The three schema-end components of a {@link Diagnostic} are filled from the declaration the reader
     * offered as it began -- the identity of the schema that declares it, the RFC 6901 pointer naming it, and
     * its own line. A schemaless read fills none of them, which is the difference this adds.
     */
    @Test
    void aRefusalNamesTheDeclarationThatJudgedIt() {
        Diagnostic refusal = read("day", "\"2026-13-99\"").refusal();
        // The canonical identity ([TSON-DATA] §2.2.1: scheme stripped), which is what entries are keyed by.
        assertEquals("example.test/atoms-1.tn", refusal.schemaId());
        assertEquals("/day", refusal.schemaPointer().orElseThrow());
        assertTrue(refusal.schemaPosition().isPresent(), "the declaration's own line");
        assertEquals("", refusal.path().orElseThrow(), "the data pointer: this value is the document root");
    }

    /** [TSON-SCHEMA] §8.3: a reference collapses where the schema is compiled for reading. */
    @Test
    void anAliasReadsAsItsTarget() {
        assertEquals(42, read("alias", "42").accepted());
    }

    /**
     * §6.5's maps are unbuilt, so that constructor compiles to a gap. A gap is <b>not a verdict</b>
     * ({@code Code.verdict()} is false), which is what keeps it from being mistaken for a statement about
     * the document -- and it costs that value a verdict and nothing else's.
     */
    @Test
    void aConstructorThisEncodingCannotYetReadIsAGapAndNotAVerdict() {
        Diagnostic gap = read("lookup", "{}").refusal();
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, gap.code());
        assertFalse(gap.code().verdict(), "a gap must not be a verdict on the document");
    }

    /** A schema whose types this encoding cannot read still compiles: the gap is per entry, not per schema. */
    @Test
    void aSchemaWithUnreadableEntriesStillCompiles() {
        assertInstanceOf(JsonTypeReader.class, COMPILED.get("lookup"));
    }
}
