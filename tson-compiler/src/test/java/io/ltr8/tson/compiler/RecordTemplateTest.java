package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;

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
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The entry a field's type names -- how every fixture here reaches the materialised instantiation. */
    private static String fieldType(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record);
        return body.fields().entrySet().stream().filter(f -> f.getKey().equals(field))
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow()
                .typeRef().name();
    }

    private static RecordField fieldOf(TsonCompiledSchema compiled, String entry, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(entry);
        return body.fields().entrySet().stream().filter(f -> f.getKey().equals(field))
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
    }

    /**
     * The records this schema minted for applications of {@code head}: an application nothing declares is its
     * expansion, a synthetic record (§5.10), and one whose expansion names itself keeps an instantiation entry
     * recording {@code head}. The fixtures apply one record template each, so every synthetic record is one.
     */
    private static List<String> instantiationsOf(TsonCompiledSchema compiled, String head) {
        var schema = compiled.schema();
        return schema.entries().keySet().stream()
                .filter(n -> schema.entries().get(n) instanceof RecordBody && schema.positionOf(n).isEmpty()
                        && (schema.factsOf(n).synthetic()
                        || schema.applicationOf(n).filter(applied -> applied.name().equals(head)).isPresent()))
                .sorted().toList();
    }

    @Test
    void aFieldPositionApplicationMaterialisesAnEntryAndReferencesIt() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<text> }""");

        List<String> made = instantiationsOf(compiled, "box");
        assertEquals(1, made.size(), () -> "expected one instantiation, got " + made);
        assertEquals(made.get(0), fieldType(compiled, "holder", "b"), "the field references it by name");

        Top entry = compiled.schema().entries().get(made.get(0));
        assertEquals(List.of(), OpenEntry.parameterNames(entry), "closed -- §5.10");
        assertEquals(TypeRef.of("text"), fieldOf(compiled, made.get(0), "v").type(), "T := text");
    }

    /**
     * An application nothing declares is its expansion (§5.10): a synthetic record that records no
     * application. A declaration naming the application records it, flattened, on its key (§8.2).
     */
    @Test
    void aMintedRecordIsItsExpansionAndADeclaredOneRecordsItsApplication() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<int32> }
                  tb     => box<text>""");

        String minted = instantiationsOf(compiled, "box").get(0);
        assertTrue(compiled.schema().factsOf(minted).synthetic(), minted);
        assertTrue(compiled.schema().applicationOf(minted).isEmpty(), "a minted expansion records no application");
        TypeRef source = compiled.schema().applicationOf("tb").orElseThrow();
        assertEquals("box", source.name());
        assertEquals(List.of(TypeRef.of("text")), source.arguments());
    }

    /**
     * A minted record is a member of nothing, so composing an application only a use site mints gives it no edge
     * to refuse: {@code vip<text>} beside a use site's {@code box<text>} loads, IS-A neither.
     */
    @Test
    void aMintedRecordComposingAMintedApplicationIsAMemberOfNothing() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  vip    => <T> box<T> & { extra: text }
                  holder => { u: vip<text>  w: box<text> }""");

        RecordBody vip = (RecordBody) compiled.schema().entries().get(fieldType(compiled, "holder", "u"));
        assertEquals(List.of(), vip.supertypes());
    }

    /** Two record templates with one field list expand to one record: the application is its expansion. */
    @Test
    void twoTemplatesExpandingToOneRecordShareOneEntry() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  crate  => <T> { v: T }
                  holder => { a: box<int32>  b: crate<int32> }""");

        assertEquals(fieldType(compiled, "holder", "a"), fieldType(compiled, "holder", "b"));
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
     * A declaration naming the application <b>is</b> the instantiation entry, not a hop to a content-named
     * one (§8.2) -- so a {@code box<text>} written elsewhere resolves to that
     * declaration rather than minting a second entry, and one entry serves both under the author's name.
     */
    @Test
    void aDeclarationPositionApplicationIsTheEntryEveryUseSiteReaches() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  text_box => box<text>
                  holder => { b: box<text> }""");

        assertEquals(List.of(), instantiationsOf(compiled, "box"),
                () -> "nothing is minted beside the declaration: " + instantiationsOf(compiled, "box"));
        assertEquals("text_box", fieldType(compiled, "holder", "b"),
                "the use site names the declaration the application denotes");
        assertEquals(TypeRef.of("text"), fieldOf(compiled, "text_box", "v").type(), "T := text");
    }

    /** An inner application closes before the outer one names it, so nesting needs no special case. */
    @Test
    void anApplicationMayBeAnArgumentToAnother() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<box<text>> }""");

        assertEquals(2, instantiationsOf(compiled, "box").size());
        String outer = fieldType(compiled, "holder", "b");
        String inner = fieldOf(compiled, outer, "v").typeRef().name();
        assertEquals(TypeRef.of("text"), fieldOf(compiled, inner, "v").type());
    }

    /**
     * Every position that can write an application resolves its arguments the same way -- a field, a
     * declaration body, and a refinement source all share one resolver. Pinned because they did not: a
     * nested-application argument resolved at a declaration position and was rejected at a field position.
     */
    @Test
    void aDeclarationPositionApplicationTakesTheSameArgumentFormsAsAFieldOne() {
        TsonCompiledSchema compiled = compile("""
                  box     => <T> { v: T }
                  nested  => box<box<text>>""");

        // The outer application is the declaration itself (#15); only the inner `box<text>`, which no
        // declaration names, is still minted.
        assertEquals(1, instantiationsOf(compiled, "box").size(),
                () -> "the inner box is minted, the outer is `nested`: " + instantiationsOf(compiled, "box"));
        assertEquals(instantiationsOf(compiled, "box").get(0), fieldOf(compiled, "nested", "v").typeRef().name(),
                "the outer's field names the inner entry");
    }

    /**
     * Regular recursion ties the knot: the recursive application reached while substituting denotes the
     * entry currently under construction, referenced by its internal name before that entry is complete.
     */
    @Test
    void aRecursiveTemplateTiesTheKnotThroughTheEntryUnderConstruction() {
        TsonCompiledSchema compiled = compile("""
                  chain => <T> { head: T  tail?: chain<T>? }
                  use => { c: chain<text> }""");

        List<String> made = instantiationsOf(compiled, "chain");
        assertEquals(1, made.size(), () -> "one entry, not an infinite family: " + made);
        assertEquals(made.get(0), fieldOf(compiled, made.get(0), "tail").typeRef().name(),
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
        assertTrue(assertThrows(ReadException.class, () -> compiled.get("holder")
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
     * A container written over the declaration's own parameter is the reference it spells, and closes with the
     * application: the field ends up naming an ordinary container entry. {@code ApplicationInContainerPositionTest}
     * carries the shape of what it closes into; here the point is only that applying the template is accepted.
     */
    @Test
    void aParameterBearingSugarFormClosesWithTheApplication() {
        for (String field : List.of("[T]", "{text => T}", "nonempty<T>")) {
            TsonCompiledSchema compiled = compile("""
                      nonempty => meta.array ^ { min_items?: = 1 }
                      tmpl     => <T> { a: %s }
                      use      => { u: tmpl<text> }""".formatted(field));

            Top closed = compiled.schema().entries()
                    .get(fieldType(compiled, fieldType(compiled, "use", "u"), "a"));
            assertEquals(List.of(), OpenEntry.parameterNames(closed), () -> field + " closes to a usable entry");
            assertNotNull(compiled.get(fieldType(compiled, "use", "u")), field);
        }
    }

    /**
     * The whole arc for a refined constructor applied over a parameter: held in the open record, constructed when the
     * record is applied, compiled, and reading real data -- and rejecting data the constructor's own pins refuse.
     */
    @Test
    void anOpenFormClosedByAnApplicationReadsRealData() {
        TsonCompiledSchema compiled = compile("""
                  two_of => meta.array ^ { min_items?: = 2  max_items?: = 2 }
                  tags   => <T> { xs: two_of<T> }
                  holder => { t: tags<text> }""");

        assertNotNull(compiled.get("holder")
                .read(TestDocuments.document("{ t: { xs: [ \"a\" \"b\" ] } }")));
        assertTrue(assertThrows(ReadException.class, () -> compiled.get("holder")
                .read(TestDocuments.document("{ t: { xs: [ \"a\" ] } }"))).getMessage().contains("2"),
                "the bound the open form states is the one that is enforced");
    }

    /**
     * §8.2's deferred check: {@code <K> { m: meta.namespace<K text> }} is a fine declaration -- {@code K} could name
     * an identifier family -- and {@code keyed<int32>} is where it stops being one, because substitution is what
     * finally hands {@code key_type} something to judge.
     */
    @Test
    void anArgumentTheSlotCannotTakeIsReportedAtTheApplication() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          keyed  => <K> { m: meta.namespace<K text> }
                          holder => { t: keyed<int32> }"""));

        assertTrue(thrown.getMessage().contains("int32"), thrown.getMessage());
    }

    /** A declaration's own body is the construction, not a reference to a lifted one -- unchanged by R2. */
    @Test
    void aDeclarationsOwnBodyStillDoesNotLift() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  ids   => [order]""");

        assertInstanceOf(io.ltr8.tson.schema.meta.ArrayBody.class,
                compiled.schema().entries().get("ids"), "the construction in place");
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
        assertTrue(assertThrows(ReadException.class, () -> compiled.get("vip")
                .read(TestDocuments.document("{ v: \"seven\"  extra: \"x\" }"))).getMessage().contains("int32"));
    }

    /**
     * Refining an application refines the closed entry, so the template's parameters are bound before the body
     * tightens anything: the field carries the argument, never an unresolved reference to a parameter the
     * author did not write.
     */
    @Test
    void refiningAnApplicationTightensTheClosedEntrysFields() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  pinned => box<text> ^ { v?: = "fixed" }""");

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
     * An application still referencing the declaration's own parameters absorbs without closing: the operand's
     * body is held, so its field set is known while the application is open, and substituting its parameters
     * with the arguments as written leaves the absorbed field carrying {@code T}. {@code
     * OpenOperandCompositionTest} carries the rest, including what such an operand does <em>not</em>
     * contribute -- its own name, a template being no type.
     */
    @Test
    void composingWithAnApplicationThatIsStillOpenAbsorbsItsFieldsAndKeepsTheParameter() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  vip => <T> box<T> & { extra: text }
                  holder => { u: vip<text> }""");

        String use = fieldType(compiled, "holder", "u");
        assertEquals(TypeRef.of("text"), fieldOf(compiled, use, "v").type(),
                "absorbed from box<T> while open, closed with vip");
        assertEquals(TypeRef.of("text"), fieldOf(compiled, use, "extra").type());
    }

    /**
     * A composition operand that is an application is absorbed by value, never closed, and absorbing it
     * resolves the head through the resolution's wait order -- which is also where an entry waiting on itself is
     * refused. A cycle reached *through* an application must still be caught there rather than recursing.
     */
    @Test
    void aCycleThroughAnApplicationIsStillCaughtAsAWaitCycle() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          a => b<text> & { x: text }
                          b => <T> a & { y: T }"""));

        assertTrue(thrown.getMessage().startsWith("'a' waits on 'b' waits on 'a'"), thrown.getMessage());
    }

    // ── Author errors, reported at the application site ──────────────────

    @Test
    void anArityMismatchIsReportedAgainstTheApplication() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          pair => <A, B> { first: A  second: B }
                          holder => { p: pair<text> }"""));
        assertTrue(thrown.getMessage().contains("takes 2 type arguments"), thrown.getMessage());
    }

    /**
     * An argument is a type, written as a reference, so a literal is no argument: {@code box<3>} is refused where
     * it is written, by the grammar (§5.10, §12.1).
     */
    @Test
    void aLiteralArgumentIsRefusedWhereItIsWritten() {
        io.ltr8.tson.base.ParseException thrown = assertThrows(io.ltr8.tson.base.ParseException.class,
                () -> compile("""
                          box => <T> { v: T }
                          holder => { b: box<3> }"""));
        assertTrue(thrown.getMessage().contains("'3' is no type argument"), thrown.getMessage());
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
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          box   => <T> { v: T }
                          weird => <T> { next?: weird<box<T>>? }
                          use   => { w: weird<text> }"""));

        assertTrue(thrown.getMessage().contains("does not pass 'T' through unchanged"), thrown.getMessage());
    }

    /** Regular recursion, by contrast, closes at one entry -- the contrast is the point of the rule. */
    @Test
    void regularRecursionClosesWhereNonRegularDoesNot() {
        assertEquals(1, instantiationsOf(compile("""
                  chain => <T> { head: T  tail?: chain<T>? }
                  use => { c: chain<text> }"""), "chain").size());
    }

    /** Applying arguments to something that declares none stays the author's error, wherever it is caught. */
    @Test
    void applyingArgumentsToANonTemplateIsAnError() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          plain => { a: text }
                          holder => { b: plain<text> }"""));
        assertTrue(thrown.getMessage().contains("declares no type parameters"), thrown.getMessage());
    }

    /**
     * <b>No alias templates</b> (§5.10): a template's body is a record body, so a body that is a bare reference -- a
     * partial application, the identity, or one applying itself -- is refused at the declaration. A use site writes
     * the application it means.
     */
    @Test
    void anAliasTemplateIsRefused() {
        for (String body : List.of("<B> pair<text, B>", "<T> T", "<T> uuid_pair<T>")) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class, () -> compile("""
                      pair      => <A, B> { first: A  second: B }
                      uuid_pair => %s""".formatted(body)), body);
            assertTrue(thrown.getMessage().contains("'uuid_pair': a template's body is a record body")
                    && thrown.getMessage().contains("alias template"), thrown.getMessage());
        }
    }

    // ── A template named as a data value's own type ──────────────────────
    //    A template is not a type (§5.10), so naming one in data is refused by
    //    OpenTemplateReader, whose message names the route: declare the application and name that. It is the
    //    author's error and an ordinary data diagnostic, never a gap in this library.

    /**
     * The mistake the target use case invites: "a page of orders" names {@code paged}, the thing the schema
     * declares. It is a data diagnostic like any other -- collectible, and located at the type-ref the
     * author wrote, not at a lifted synthetic.
     */
    @Test
    void namingATemplateAsADataTypeIsADataDiagnostic() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  paged => <T> { items: [T] }
                  orders_page => paged<order>""");
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();

        Object value = compiled.get("paged")
                .read(TestDocuments.document("!paged { items: [ { id: \"a\" } ] }", problems));

        assertNull(value);
        assertEquals(1, problems.diagnostics().size(), () -> problems.diagnostics().toString());
        Diagnostic problem = problems.diagnostics().get(0);
        // The name resolves -- to a template, which is no type, so the message names the route: declare the
        // application and write that name.
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problem.code());
        assertTrue(problem.message().contains("'paged' is a template"), problem.message());
        assertTrue(problem.message().contains("'my_type => paged<...>'"),
                "the route out of it is a declaration of the application: " + problem.message());
        // The applied form is a type and still reads, which is what the message points at.
        assertNotNull(compiled.get("orders_page")
                .read(TestDocuments.document("{ items: [ { id: \"a\" } ] }")));
    }

    /**
     * The same for a template whose body needs no lifting at all: {@code box}'s field type is the parameter
     * itself, and the template is refused before any reader for {@code T} is reached.
     *
     * <p><b>And the declared application reads</b>, which is the other half of the rule: {@code !box} names a
     * template and is refused, {@code int_box} is a type.
     */
    @Test
    void aTemplateWhoseFieldIsTheParameterIsRefusedButItsMemberReads() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  int_box => box<int32>""");
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();

        compiled.get("box").read(TestDocuments.document("!box { v: 1 }", problems));

        assertEquals(1, problems.diagnostics().size(), () -> problems.diagnostics().toString());
        assertTrue(problems.diagnostics().get(0).message().contains("'box' is a template"),
                problems.diagnostics().get(0).message());

        assertEquals(List.of(), collect(compiled, "int_box", "{ v: 1 }"),
                "the declared application is a type and reads");
    }

    /** A fail-fast read throws the read exception every other data problem throws -- never a library fault. */
    @Test
    void aFailFastReadOfATemplateThrowsAReadException() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  int_box => box<int32>""");

        ReadException thrown = assertThrows(ReadException.class,
                () -> compiled.get("box").read(TestDocuments.document("!box { v: 1 }")));

        assertTrue(thrown.getMessage().contains("a template is not a type"), thrown.getMessage());
    }

    /** Every diagnostic one document collects at {@code rootType}. */
    private static List<Diagnostic> collect(TsonCompiledSchema compiled, String rootType, String document) {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        compiled.get(rootType).read(TestDocuments.document(document, problems));
        return List.copyOf(problems.diagnostics());
    }
}
