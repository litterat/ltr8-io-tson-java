package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;

import java.math.BigInteger;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.10 materialisation for <b>record</b> templates -- the form whose parameters occupy field types and
 * field values. Applying one closes it: the arguments are substituted into the template's recorded open
 * form and the application is replaced by a reference to the entry that results
 * ({@code TemplateMaterialiser}).
 *
 * <p>A template whose body writes a §5.3 container sugar form closes here too, by the other path: the form
 * lifts to an open synthetic and the application closes it into an ordinary container entry. The shape of
 * what it closes into is {@code ContainerSugarEndToEndTest}'s; what these fixtures pin is that applying such
 * a template works at all, and that a value parameter reaches the slot it was written for.
 */
class RecordTemplateTest {

    private static final String ID = "https://example.test/template.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/template.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
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

    /** The entry a field's type names -- how every fixture here reaches the materialised instantiation. */
    private static String fieldType(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record).body();
        return body.fields().stream().filter(f -> f.name().equals(field)).findFirst().orElseThrow()
                .type().name();
    }

    private static RecordField fieldOf(TsonCompiledSchema compiled, String entry, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(entry).body();
        return body.fields().stream().filter(f -> f.name().equals(field)).findFirst().orElseThrow();
    }

    /** Entry names that look materialised -- derived from {@code head} rather than declared. */
    private static List<String> instantiationsOf(TsonCompiledSchema compiled, String head) {
        return compiled.schema().entries().keySet().stream().filter(n -> n.startsWith(head + "_")).sorted().toList();
    }

    @Test
    void aFieldPositionApplicationMaterialisesAnEntryAndReferencesIt() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<text> }""");

        List<String> made = instantiationsOf(compiled, "box");
        assertEquals(1, made.size(), () -> "expected one instantiation, got " + made);
        assertEquals(made.get(0), fieldType(compiled, "holder", "b"), "the field references it by name");

        TypeDefinition entry = compiled.schema().entries().get(made.get(0));
        assertEquals(List.of(), entry.parameters(), "closed -- §5.10");
        assertEquals(TypeRef.of("text"), fieldOf(compiled, made.get(0), "v").type(), "T := text");
    }

    /**
     * §8.2 keys an instantiation on the flattened application recorded in {@code source}, which is what a
     * consumer compares by -- never the internal name.
     */
    @Test
    void anInstantiationRecordsTheFlattenedApplicationAsItsSource() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<text> }""");

        TypeRef source = compiled.schema().entries().get(instantiationsOf(compiled, "box").get(0))
                .source().orElseThrow();
        assertEquals("box", source.name());
        assertEquals(List.of(new TypeArgument.Ref(TypeRef.of("text"))), source.arguments());
    }

    /** Two applications with the same arguments are one entry, wherever in the schema they appear (§8.2). */
    @Test
    void twoIdenticalApplicationsShareOneEntry() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  first  => { b: box<text> }
                  second => { c: box<text> }""");

        assertEquals(1, instantiationsOf(compiled, "box").size());
        assertEquals(fieldType(compiled, "first", "b"), fieldType(compiled, "second", "c"));
    }

    @Test
    void differentArgumentsGiveDifferentEntries() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { a: box<text>  b: box<int32> }""");

        assertEquals(2, instantiationsOf(compiled, "box").size());
    }

    /**
     * A declaration naming the application is an <b>alias</b> to the instantiation entry, not a second copy
     * of it -- so a `box<text>` written elsewhere lands on the same entry rather than on this name.
     */
    @Test
    void aDeclarationPositionApplicationAliasesTheSameEntry() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  text_box => box<text>
                  holder => { b: box<text> }""");

        assertEquals(1, instantiationsOf(compiled, "box").size());
        String made = instantiationsOf(compiled, "box").get(0);
        assertEquals(made, fieldType(compiled, "holder", "b"));
        assertEquals(TypeRef.of(made),
                assertInstanceOf(io.ltr8.tson.schema.meta.Reference.class,
                        compiled.schema().entries().get("text_box").body()).target());
    }

    /** A value parameter binds the literal it was applied with, and the route is gone once bound (§5.10). */
    @Test
    void aValueParameterBindsTheAppliedLiteral() {
        TsonCompiledSchema compiled = compile("""
                  retry => <N> { attempts: int32 ~ N }
                  holder => { r: retry<3> }""");

        RecordField attempts = fieldOf(compiled, fieldType(compiled, "holder", "r"), "attempts");
        assertEquals("3", attempts.value().orElseThrow().text());
        assertTrue(attempts.valueParam().isEmpty(), "the route is spent once the value is bound");
    }

    /** An inner application closes before the outer one names it, so nesting needs no special case. */
    @Test
    void anApplicationMayBeAnArgumentToAnother() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<box<text>> }""");

        assertEquals(2, instantiationsOf(compiled, "box").size());
        String outer = fieldType(compiled, "holder", "b");
        String inner = fieldOf(compiled, outer, "v").type().name();
        assertEquals(TypeRef.of("text"), fieldOf(compiled, inner, "v").type());
    }

    /**
     * Every position that can write an application resolves its arguments the same way -- a field, a
     * declaration body, and a refinement source all share one resolver. Pinned because they did not: a
     * value or nested-application argument resolved at a declaration position and was rejected at a field
     * position, so `box<3>` and `box<box<text>>` worked in one place and not the other.
     */
    @Test
    void aDeclarationPositionApplicationTakesTheSameArgumentFormsAsAFieldOne() {
        TsonCompiledSchema compiled = compile("""
                  box     => <T> { v: T }
                  counted => <N> { n: int32 ~ N }
                  nested  => box<box<text>>
                  three   => counted<3>""");

        assertEquals(2, instantiationsOf(compiled, "box").size(), "the inner and outer box");
        assertEquals("3", fieldOf(compiled, instantiationsOf(compiled, "counted").get(0), "n")
                .value().orElseThrow().text());
    }

    /**
     * Regular recursion ties the knot: the recursive application reached while substituting denotes the
     * entry currently under construction, referenced by its internal name before that entry is complete.
     */
    @Test
    void aRecursiveTemplateTiesTheKnotThroughTheEntryUnderConstruction() {
        TsonCompiledSchema compiled = compile("""
                  chain => <T> { head: T  tail: chain<T>? }
                  use => { c: chain<text> }""");

        List<String> made = instantiationsOf(compiled, "chain");
        assertEquals(1, made.size(), () -> "one entry, not an infinite family: " + made);
        assertEquals(made.get(0), fieldOf(compiled, made.get(0), "tail").type().name(),
                "the tail references the entry it sits in");
    }

    /** The whole arc: a materialised template reads real data. */
    @Test
    void aMaterialisedTemplateReadsRealData() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<int32> }""");

        TsonValue value = (TsonValue) compiled.get("holder")
                .read(TestDocuments.document("{ b: { v: 7 } }"));
        assertNotNull(value);
        assertTrue(assertThrows(TsonReadException.class, () -> compiled.get("holder")
                .read(TestDocuments.document("{ b: { v: \"seven\" } }"))).getMessage().contains("int32"));
    }

    // ── Sugar inside a template body (R2) ────────────────────────────────
    //    A *concrete* form lifts to an ordinary closed entry at desugar, exactly as it would outside a
    //    template, so the template that holds it applies like any other. Only a form written over one of
    //    the declaration's own parameters has to wait for the open representation.

    @Test
    void aConcreteSugarFormInsideATemplateLiftsAndTheTemplateApplies() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  tmpl  => <T> { a: T  b: [order] }
                  use   => { u: tmpl<text> }""");

        assertEquals(1, instantiationsOf(compiled, "tmpl").size());
        assertEquals(TypeRef.of("text"), fieldOf(compiled, fieldType(compiled, "use", "u"), "a").type());
        assertNotNull(compiled.get("use")
                .read(TestDocuments.document("{ u: { a: \"x\"  b: [ { id: \"1\" } ] } }")));
    }

    /** Every sugar form, not just the array: choice, map, and a nested bracket form all lift concretely. */
    @Test
    void everyConcreteSugarFormLiftsFromATemplateBody() {
        for (String field : List.of("[order]", "(order | text)", "[[order]]", "{text => order}")) {
            assertNotNull(compile("""
                      order => { id: text }
                      tmpl  => <T> { a: T  b: %s }
                      use   => { u: tmpl<text> }""".formatted(field)), field);
        }
    }

    /**
     * The lifted entry is an ordinary one, so it is the same entry a directly written `[order]` produces
     * anywhere else in the schema -- the cross-channel dedup that makes lifting concrete forms eagerly the
     * right move rather than merely a convenient one.
     */
    @Test
    void aFormLiftedFromATemplateIsTheSameEntryADirectOneProduces() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  tmpl  => <T> { a: T  b: [order] }
                  plain => { c: [order] }
                  use   => { u: tmpl<text> }""");

        assertEquals(1, compiled.schema().entries().keySet().stream()
                .filter(n -> n.startsWith("array_order_")).count(),
                () -> "one array_order entry, shared: " + compiled.schema().entries().keySet());
    }

    /**
     * A form written over the declaration's own parameter closes with it: the open synthetic it lifted to is
     * closed innermost-out as the application that reaches it is, so the field ends up naming an ordinary
     * container entry. {@code ContainerSugarEndToEndTest} carries the shape of what it closes into; here the
     * point is only that applying the template is no longer refused.
     */
    @Test
    void aParameterBearingSugarFormClosesWithTheApplication() {
        for (String field : List.of("[T]", "[T; 1..2]", "{text => T}")) {
            TsonCompiledSchema compiled = compile("""
                      box  => <T> { v: T }
                      tmpl => <T> { a: %s }
                      use  => { u: tmpl<text> }""".formatted(field));

            TypeDefinition closed = compiled.schema().entries()
                    .get(fieldType(compiled, fieldType(compiled, "use", "u"), "a"));
            assertEquals(List.of(), closed.parameters(), () -> field + " closes to a usable entry");
            assertNotNull(compiled.get(fieldType(compiled, "use", "u")), field);
        }
    }

    /**
     * The whole arc for an open instance: the sugar over a parameter, closed by the application, compiled,
     * and reading real data -- and rejecting data the closed form's own constraints refuse.
     */
    @Test
    void anOpenFormClosedByAnApplicationReadsRealData() {
        TsonCompiledSchema compiled = compile("""
                  tags   => <N> { xs: [text; N] }
                  holder => { t: tags<2> }""");

        assertNotNull(compiled.get("holder")
                .read(TestDocuments.document("{ t: { xs: [ \"a\" \"b\" ] } }")));
        assertTrue(assertThrows(TsonReadException.class, () -> compiled.get("holder")
                .read(TestDocuments.document("{ t: { xs: [ \"a\" ] } }"))).getMessage().contains("2"),
                "the bound the parameter supplied is the one that is enforced");
    }

    /**
     * §8.2's deferred value-level check, at the one place D7 gives it a home. {@code <N> [text; N]} is a fine
     * declaration -- {@code N} could be anything -- and {@code <"two">} is where it stops being one, because
     * substitution is what finally hands {@code min_items} something to read.
     */
    @Test
    void anArgumentTheSlotCannotTakeIsReportedAtTheApplication() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          tags   => <N> { xs: [text; N] }
                          holder => { t: tags<"two"> }"""));

        assertTrue(thrown.getMessage().contains("not a valid integer"), thrown.getMessage());
    }

    /**
     * A parameter passed through as an <em>argument</em> keeps the channel it was applied on. An argument
     * list is the one position where a type and a value are equally at home, so the value {@code N} carries
     * reaches the open synthetic's own {@code min_items} rather than being rejected as a type.
     */
    @Test
    void aValueParameterReachesAnOpenSyntheticThroughTheApplication() {
        TsonCompiledSchema compiled = compile("""
                  tags   => <N> { xs: [text; N] }
                  holder => { t: tags<2> }""");

        ArrayBody closed = (ArrayBody) compiled.schema().entries()
                .get(fieldType(compiled, fieldType(compiled, "holder", "t"), "xs")).body();

        assertEquals(Optional.of(BigInteger.TWO), closed.minItems());
        assertEquals(Optional.of(BigInteger.TWO), closed.maxItems());
    }

    /** A declaration's own body is the construction, not a reference to a lifted one -- unchanged by R2. */
    @Test
    void aDeclarationsOwnBodyStillDoesNotLift() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  ids   => [order]""");

        assertInstanceOf(io.ltr8.tson.schema.meta.ArrayBody.class,
                compiled.schema().entries().get("ids").body(), "the construction in place");
    }

    // ── The two field-absorbing positions (§5.7, §5.8) ───────────────────
    //    A composition supertype and a refinement source copy the source's *fields*, so an application
    //    there has to be closed during resolution rather than carried and closed by the later pass --
    //    an open application has no field set to copy.

    @Test
    void composingWithAnApplicationAbsorbsTheClosedEntrysFields() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  vip => box<int32> & { extra: text }""");

        assertEquals(TypeRef.of("int32"), fieldOf(compiled, "vip", "v").type(), "absorbed, with T bound");
        assertEquals(TypeRef.of("text"), fieldOf(compiled, "vip", "extra").type());
        assertNotNull(compiled.get("vip").read(TestDocuments.document("{ v: 7  extra: \"x\" }")));
        assertTrue(assertThrows(TsonReadException.class, () -> compiled.get("vip")
                .read(TestDocuments.document("{ v: \"seven\"  extra: \"x\" }"))).getMessage().contains("int32"));
    }

    /**
     * Refinement previously copied the template's body with its parameters <em>unbound</em>, and the author
     * was told about an unresolved reference to a parameter they never wrote -- silently wrong rather than
     * loudly unimplemented, which is why this is pinned rather than merely enabled.
     */
    @Test
    void refiningAnApplicationTightensTheClosedEntrysFields() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  pinned => box<text> ^ { v: = "fixed" }""");

        assertEquals(TypeRef.of("text"), fieldOf(compiled, "pinned", "v").type());
        assertEquals("fixed", fieldOf(compiled, "pinned", "v").value().orElseThrow().text());
    }

    /** Both positions reach the same entry the ordinary type positions do -- one application, one entry. */
    @Test
    void anAbsorbingPositionSharesTheEntryWithAnOrdinaryOne() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  vip => box<text> & { extra: text }
                  holder => { b: box<text> }""");

        assertEquals(1, instantiationsOf(compiled, "box").size());
        assertEquals(instantiationsOf(compiled, "box").get(0), fieldType(compiled, "holder", "b"));
    }

    /**
     * An application still referencing the declaration's own parameters cannot close: deferring composition
     * itself until the enclosing template materialises is a different feature. The message says that rather
     * than blaming missing substitution, which now exists.
     */
    @Test
    void composingWithAnApplicationThatIsStillOpenIsRefusedForWhatItActuallyNeeds() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> compile("""
                          box => <T> { v: T }
                          vip => <T> box<T> & { extra: text }"""));

        assertTrue(thrown.getMessage().contains("is still open"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("this declaration's own parameter 'T'"), thrown.getMessage());
    }

    /**
     * Closing on demand resolves the head through {@code SchemaResolver}'s namespace getter -- which is also
     * the memo the circular-composition guard rides on. A cycle reached *through* an application must still
     * be caught there rather than recursing.
     */
    @Test
    void aCycleThroughAnApplicationIsStillCaughtAsACircularComposition() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          a => b<text> & { x: text }
                          b => <T> a & { y: T }"""));

        assertTrue(thrown.getMessage().contains("circular composition/refinement chain"), thrown.getMessage());
    }

    // ── Author errors, reported at the application site ──────────────────

    @Test
    void anArityMismatchIsReportedAgainstTheApplication() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          pair => <A, B> { first: A  second: B }
                          holder => { p: pair<text> }"""));
        assertTrue(thrown.getMessage().contains("takes 2 type arguments"), thrown.getMessage());
    }

    @Test
    void applyingAValueWhereTheBodyUsesAParameterAsATypeIsAnError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          box => <T> { v: T }
                          holder => { b: box<3> }"""));
        assertTrue(thrown.getMessage().contains("used as a type"), thrown.getMessage());
    }

    @Test
    void applyingATypeWhereTheBodyRoutesAValueIsAnError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          retry => <N> { attempts: int32 ~ N }
                          holder => { r: retry<text> }"""));
        assertTrue(thrown.getMessage().contains("as a value"), thrown.getMessage());
    }

    /**
     * <b>Non-regular recursion never reaches materialisation.</b> {@code weird<text>} would close to
     * {@code weird<box<text>>}, then {@code weird<box<box<text>>>}, … -- the argument grows every level, so
     * every instantiation is distinct and the knot-tying memo never fires. {@code TemplateRegularity}
     * rejects the declaration before any of that, so this is the author's error rather than a library gap,
     * and it does not depend on anyone applying the template. The full rule is exercised by
     * {@code TemplateRegularityTest}; this pins the contrast with the regular case below.
     */
    @Test
    void nonRegularRecursionIsRejectedBeforeAnythingCloses() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          box   => <T> { v: T }
                          weird => <T> { next: weird<box<T>>? }
                          use   => { w: weird<text> }"""));

        assertTrue(thrown.getMessage().contains("does not pass 'T' through unchanged"), thrown.getMessage());
    }

    /** Regular recursion, by contrast, closes at one entry -- the contrast is the point of the rule. */
    @Test
    void regularRecursionClosesWhereNonRegularDoesNot() {
        assertEquals(1, instantiationsOf(compile("""
                  chain => <T> { head: T  tail: chain<T>? }
                  use => { c: chain<text> }"""), "chain").size());
    }

    /** Applying arguments to something that declares none stays the author's error, wherever it is caught. */
    @Test
    void applyingArgumentsToANonTemplateIsAnError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          plain => { a: text }
                          holder => { b: plain<text> }"""));
        assertTrue(thrown.getMessage().contains("declares no type parameters"), thrown.getMessage());
    }

    // ── A template named as a data value's own type ──────────────────────
    //    A template is not a type until it is applied, and a data type-ref carries no arguments -- so
    //    naming one in data is the author's error, not a gap in this library. See OpenTemplateReader.

    /**
     * The mistake the target use case invites: "a page of orders" names {@code paged}, the thing the schema
     * declares. It is a data diagnostic like any other -- collectible, and located at the type-ref the
     * author wrote -- where it used to reach the lifted synthetic and exit on the library's own fault code
     * complaining about a missing {@code instance_template} factory.
     */
    @Test
    void namingATemplateAsADataTypeIsADataDiagnostic() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  paged => <T> { items: [T] }
                  orders_page => paged<order>""");
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        Object value = compiled.get("paged")
                .read(TestDocuments.document("!paged { items: [ { id: \"a\" } ] }", problems));

        assertNull(value);
        assertEquals(1, problems.diagnostics().size(), () -> problems.diagnostics().toString());
        Diagnostic problem = problems.diagnostics().get(0);
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, problem.code());
        assertTrue(problem.message().contains("'paged' is a template taking 1 type argument [T]"),
                problem.message());
        assertTrue(problem.message().contains("my_type => paged<...>"), "the route out of it: " + problem.message());
        assertEquals("!paged", problem.actual());
        // The applied form is a type and still reads, which is what the message points at.
        assertNotNull(compiled.get("orders_page")
                .read(TestDocuments.document("{ items: [ { id: \"a\" } ] }")));
    }

    /**
     * The same for a template whose body needs no lifting at all: {@code box}'s field type is the parameter
     * itself, which used to compile to an {@link io.ltr8.tson.compiler.reader.ErrorReader} whose message
     * blamed the linker for not rejecting {@code T}. The entry is refused before any of that.
     */
    @Test
    void aTemplateWhoseFieldIsTheParameterIsRefusedTheSameWay() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  int_box => box<int32>""");
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        compiled.get("box").read(TestDocuments.document("!box { v: 1 }", problems));

        assertEquals(1, problems.diagnostics().size(), () -> problems.diagnostics().toString());
        assertTrue(problems.diagnostics().get(0).message().startsWith("'box' is a template taking"),
                problems.diagnostics().get(0).message());
    }

    /** A fail-fast read throws the read exception every other data problem throws -- never a library fault. */
    @Test
    void aFailFastReadOfATemplateThrowsAReadException() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  int_box => box<int32>""");

        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> compiled.get("box").read(TestDocuments.document("!box { v: 1 }")));

        assertTrue(thrown.getMessage().contains("is a template taking"), thrown.getMessage());
    }
}
