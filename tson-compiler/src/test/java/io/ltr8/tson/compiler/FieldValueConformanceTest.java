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
 * [TSON-SCHEMA] §5.2's dependency between a field's two halves: a {@code ~} default or {@code =} fixed value
 * must be a value of the field's own declared type. meta-kernel states the rule on {@code record_field.value}
 * and calls it "a dependency the schema language does not express directly", which is what leaves it to
 * {@code TsonSchemaLinker} to check.
 *
 * <p><b>Where the verdict lands is half the point.</b> The same decode runs anyway when the record's reader
 * is built, so the mistake was always detected -- as an {@code ErrorReader}, which meant the author's own
 * {@code tson compile} passed and the failure surfaced later to whoever sent data, coded as a gap in this
 * library. Checking at link makes it the author's error, against their own declaration, before anything is
 * compiled: these fixtures assert the verdict, the two names it has to carry, and -- in
 * {@link #collectingModeReportsEveryOffendingDeclaration} -- that it lands on the declaration that wrote
 * it, one per offender.
 */
class FieldValueConformanceTest {

    private static final String ID = "https://example.test/field-value.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/field-value.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
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
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static TsonSchemaValidationException refused(String declarations) {
        return assertThrows(TsonSchemaValidationException.class, () -> compile(declarations));
    }

    /**
     * The plain case, and the one that used to reach a data sender as {@code NOT_IMPLEMENTED}. Both halves
     * of the field are named -- the type it declares and the value it defaults to -- because reconciling
     * them is the whole of the fix and either one alone leaves the author guessing which to change.
     */
    @Test
    void aDefaultThatIsNotAValueOfTheFieldsTypeIsRefused() {
        TsonSchemaValidationException thrown = refused("rec => { first: int32 ~ \"nope\"  other: int32 }");

        assertTrue(thrown.getMessage().contains("field 'first'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("declared 'int32'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.2"), thrown.getMessage());
    }

    /** A fixed value is the same rule, and says "fixed value" rather than "default" so the author's own spelling is echoed. */
    @Test
    void aFixedValueThatIsNotAValueOfTheFieldsTypeIsRefused() {
        TsonSchemaValidationException thrown = refused("rec => { first: int32 = \"nope\" }");

        assertTrue(thrown.getMessage().contains("fixed value"), thrown.getMessage());
    }

    /**
     * <b>Not only the grammar: the type's own facets too.</b> {@code 500} is a perfectly good integer and
     * still not a value of this field's type, which is exactly what makes the field's <em>resolved</em> body
     * the thing to check against rather than the built-in the name started from. The atom's own message
     * carries the bound that failed.
     */
    @Test
    void aDefaultOutsideTheTypesOwnConstraintsIsRefused() {
        TsonSchemaValidationException thrown = refused("""
                  small => !integer ^ { max: 100 }
                  rec   => { n: small ~ 500 }""");

        assertTrue(thrown.getMessage().contains("field 'n'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("100"), thrown.getMessage());
    }

    /**
     * <b>An enum member is checked by membership</b>, which is the other family a field's value routinely
     * names. Nothing about the check is integer-specific: it is the field's own resolved body parsing its
     * own token, whatever family that body belongs to.
     */
    @Test
    void aDefaultThatIsNotAMemberOfTheFieldsEnumIsRefused() {
        TsonSchemaValidationException thrown = refused("""
                  status => !enum [ PENDING SHIPPED ]
                  rec    => { s: status ~ CANCELLED }""");

        assertTrue(thrown.getMessage().contains("field 's'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("PENDING"), thrown.getMessage());
    }

    /**
     * <b>A quoted numeric at an integer-typed field is accepted, and that is the spec's answer, not a hole
     * in this check.</b> [TSON-DATA] §7.4: "a token's form is consulted exactly once: by base type
     * resolution (§4) ... Everywhere else only the text matters. Type contracts operate on text --
     * {@code !number 10.2}, {@code !number "10.2"} and {@code !number """10.2"""} are the same value". A
     * field's declared type is a type contract, so §4 never runs here and the quotes carry no meaning to
     * consult.
     *
     * <p>Pinned because the opposite is the intuitive reading and was written down as a defect once already:
     * §4.4's "any quoted token resolves to a string" is about <em>untyped</em> tokens, and carrying it into a
     * typed position would reject documents the spec requires an implementation to accept. The check above
     * cannot drift on this in either direction -- it runs the field's own reader parser, so it accepts a
     * default exactly when a read would accept the same token in the same position.
     */
    @Test
    void aQuotedNumericIsAValueOfAnIntegerFieldBecauseFormIsNotMeaning() {
        assertNotNull(compile("rec => { n: int32 ~ \"3\"  m: float64 = \"1.5\" }"));
    }

    /** A conforming value is left alone, at both spellings and across families. */
    @Test
    void aValueOfTheFieldsOwnTypeIsAccepted() {
        assertNotNull(compile("""
                  status => !enum [ PENDING SHIPPED ]
                  rec    => {
                    n: int32 ~ 3
                    label: text = "fixed"
                    s: status ~ PENDING
                    when: date ~ "2020-01-01"
                  }"""));
    }

    /**
     * <b>The optional-fixed-to-absent spelling has no value to check</b> (§5.2's sixth): {@code = _} resolves
     * to {@code OPTIONAL_FIXED} carrying nothing at all, so there is no token here for a type to reject.
     */
    @Test
    void fixedToAbsentCarriesNoValueAndIsUntouched() {
        assertNotNull(compile("rec => { n: int32?  m: int32? = _ }"));
    }

    /**
     * <b>A container-typed field cannot have one at all</b>, and the field's type alone settles it: no token
     * is an array, map or tuple value, whatever it spells. §12.1 admits only a bare token after {@code
     * ~}/{@code =} -- {@code ~ []} and {@code ~ {}} are syntax errors, not other values -- so there is no
     * better token to suggest, which is why the message is about the field rather than about the value.
     */
    @Test
    void aContainerTypedFieldCannotCarryAValueAtAll() {
        for (String container : List.of("[text]", "{text => int32}", "[text, int32]")) {
            TsonSchemaValidationException thrown = refused("""
                      ns  => %s
                      rec => { xs: ns ~ oops }""".formatted(container));

            assertTrue(thrown.getMessage().contains("field 'xs'"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("cannot have a default"), thrown.getMessage());
        }
    }

    /**
     * <b>A record and a choice are refused too, and this is the case that makes the rule a rule.</b> A token
     * <em>can</em> reach both: §5.6's positional form fills a record with exactly one bare {@code REQUIRED}
     * field from a bare value, and a choice discriminates a token to an atom-typed variant -- so a read
     * would accept {@code point ~ 3} and {@code ( int32 | text ) ~ oops}. Admitting them would make "may
     * this field have a default?" depend on the referenced type's field count and variant list, which is a
     * rule an author computes rather than remembers.
     *
     * <p>So the answer is one line -- <b>a fixed or default value is available on a scalar-typed field and
     * nowhere else</b> -- and it costs those two spellings. That is §5.2's "Which fields may carry a value"
     * verbatim, down to the reason: §5.6 is a spelling rule for data values, not a claim that a record
     * <em>is</em> a token.
     */
    @Test
    void aRecordOrChoiceTypedFieldIsRefusedEvenWhereAReadWouldAcceptTheToken() {
        TsonSchemaValidationException positional = refused("""
                  point => { n: int32 }
                  rec   => { p: point ~ 3 }""");
        assertTrue(positional.getMessage().contains("which is a record"), positional.getMessage());
        assertTrue(positional.getMessage().contains("declare the field with a scalar type"),
                positional.getMessage());

        TsonSchemaValidationException variant = refused("""
                  ch  => ( int32 | text )
                  rec => { c: ch ~ oops }""");
        assertTrue(variant.getMessage().contains("which is a choice"), variant.getMessage());
    }

    /**
     * <b>{@code void} is not a scalar either</b>, and shares its resolved shape with {@code value} and
     * {@code token} -- three declarations, one deliberately uninformative {@code unit} body, told apart by
     * the declaration's own name, which §4.2 makes the normative dispatch. So the check asks for the name
     * as well as the body, exactly as the reader stack's own {@code unit} factory does.
     */
    @Test
    void voidIsNotAScalarAndCannotCarryAValue() {
        TsonSchemaValidationException thrown = refused("rec => { v: void ~ anything }");

        assertTrue(thrown.getMessage().contains("the void type"), thrown.getMessage());
    }

    /**
     * <b>Every bad field is reported, not just the first.</b> The check runs where a failed declaration
     * already leaves an answer-everything placeholder, so it inherits the javac-style single pass rather
     * than needing one of its own -- an author with two of these fixes both from one run.
     */
    @Test
    void collectingModeReportsEveryOffendingDeclaration() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        String schema = """
                !!id:"https://example.test/field-value.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  a => { n: int32 ~ "nope" }
                  b => { m: int32 = "also nope" }
                }
                """;
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);

        TsonCompiledSchema compiled = TsonCompiledSchemaRegistry.tree(core).get(ID, problems);

        assertEquals(null, compiled, "a schema that reported is never registered");
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR, Diagnostic.Code.SCHEMA_ERROR),
                problems.diagnostics().stream().map(Diagnostic::code).toList(),
                problems.diagnostics()::toString);
        assertEquals(List.of("/a", "/b"),
                problems.diagnostics().stream().map(d -> d.schemaPointer().orElseThrow()).toList());
    }
}
