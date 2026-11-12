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
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.10 materialisation for <b>record</b> templates -- the form whose parameters occupy field types. Applying one
 * closes it: the arguments are substituted into the open record, under the declaration that names the application,
 * or in place at the type slot that wrote it ({@code TemplateMaterialiser}).
 *
 * <p>A template whose body writes a §5.3 container sugar form closes here too: a form over a parameter is held as the
 * application it spells and closes to its construction in place when the template is applied. The shape of what it
 * closes into is {@code ContainerSugarEndToEndTest}'s; what these fixtures pin is that applying such a template works
 * at all, and that a parameter reaches the slot it was written for.
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

    private static RecordField fieldOf(TsonCompiledSchema compiled, String entry, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(entry);
        return body.fields().entrySet().stream().filter(f -> f.getKey().equals(field))
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
    }

    /** The record a field's type holds in place, where an application of an open record closed. */
    private static TypeInline inPlace(TsonCompiledSchema compiled, String record, String field) {
        return assertInstanceOf(TypeInline.class, fieldOf(compiled, record, field).type(), record + "." + field);
    }

    /** The field {@code field} of the record a type written in place holds. */
    private static RecordField fieldOf(TypeInline inPlace, String field) {
        return ((RecordBody) inPlace.of()).fields().get(field);
    }

    /** Every entry a schema holds that records an application of {@code head} on its key (§8.2). */
    private static List<String> instantiationsOf(TsonCompiledSchema compiled, String head) {
        var schema = compiled.schema();
        return schema.entries().keySet().stream()
                .filter(n -> schema.applicationOf(n).filter(applied -> applied.head().name().equals(head)).isPresent())
                .sorted().toList();
    }

    /**
     * An application at a field closes in place (§5.10): the substituted record, closed, with no name and no chain,
     * held at the field, and nothing added to the schema for it.
     */
    @Test
    void aFieldPositionApplicationClosesInPlace() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<text> }""");

        TypeInline b = inPlace(compiled, "holder", "b");
        RecordBody record = assertInstanceOf(RecordBody.class, b.of());
        assertEquals(List.of(), OpenEntry.parameterNames(record), "closed -- §5.10");
        assertEquals(TypeChain.EMPTY, record.chain(), "no chain");
        assertEquals(TypeRef.of("text"), fieldOf(b, "v").type(), "T := text");
        assertEquals("box<text>", b.text(), "named in a message by the application as written");
        assertEquals(List.of(), instantiationsOf(compiled, "box"), "no entry records the application");
    }

    /**
     * A record in place records no application (§8.1); a declaration naming the application records it on its key
     * (§8.2).
     */
    @Test
    void aRecordInPlaceRecordsNoApplicationAndADeclaredOneDoes() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { b: box<int32> }
                  tb     => box<text>""");

        assertEquals(List.of("tb"), instantiationsOf(compiled, "box"));
        io.ltr8.tson.schema.meta.ApplyBody source = compiled.schema().applicationOf("tb").orElseThrow();
        assertEquals("box", source.head().name());
        assertEquals(List.of(TypeRef.of("text")), source.arguments());
    }

    /**
     * A record in place is a member of nothing, so composing an application no declaration names gives it no edge
     * to refuse: {@code vip<text>} beside a use site's {@code box<text>} loads, carrying no chain.
     */
    @Test
    void aRecordInPlaceComposingAnUndeclaredApplicationIsAMemberOfNothing() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  vip    => <T> box<T> & { extra: text }
                  holder => { u: vip<text>  w: box<text> }""");

        assertEquals(TypeChain.EMPTY, inPlace(compiled, "holder", "u").of().chain());
    }

    /**
     * Two open records with one field list close to equal records, one type by §8.2's structural identity, each
     * named in a message by its own application.
     */
    @Test
    void twoOpenRecordsClosingToOneRecordAreOneType() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  crate  => <T> { v: T }
                  holder => { a: box<int32>  b: crate<int32> }""");

        TypeInline a = inPlace(compiled, "holder", "a");
        TypeInline b = inPlace(compiled, "holder", "b");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("box<int32>", a.text());
        assertEquals("crate<int32>", b.text());
    }

    /** Two applications with the same arguments close to one type, wherever in the schema they appear (§8.2). */
    @Test
    void twoIdenticalApplicationsAreOneType() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  first  => { b: box<text> }
                  second => { c: box<text> }""");

        assertEquals(inPlace(compiled, "first", "b"), inPlace(compiled, "second", "c"));
        assertEquals(List.of(), instantiationsOf(compiled, "box"));
    }

    @Test
    void differentArgumentsGiveDifferentTypes() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  holder => { a: box<text>  b: box<int32> }""");

        assertNotEquals(inPlace(compiled, "holder", "a"), inPlace(compiled, "holder", "b"));
    }

    /**
     * An application at a slot is never looked up to a declaration (§5.10): {@code b: box<text>} beside {@code
     * text_box => box<text>} closes in place, a type of the same shape that is not {@code text_box}.
     */
    @Test
    void aUseSiteApplicationIsNeverLookedUpToItsDeclaration() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  text_box => box<text>
                  holder => { b: box<text> }""");

        TypeInline b = inPlace(compiled, "holder", "b");
        assertEquals(compiled.schema().entries().get("text_box"), b.of(), "one shape");
        assertEquals(List.of("text_box"), instantiationsOf(compiled, "box"));
        assertEquals(TypeRef.of("text"), fieldOf(compiled, "text_box", "v").type(), "T := text");
    }

    /**
     * Regular recursion ties the knot at the declaration closing it (§5.10.1): the recursive application reached
     * while substituting names that declaration, and the same application at a use site, with no declaration
     * closing it, is refused.
     */
    @Test
    void aRecursiveTemplateTiesTheKnotThroughItsDeclaration() {
        TsonCompiledSchema compiled = compile("""
                  chain      => <T> { head: T  tail?: chain<T>? }
                  text_chain => chain<text>
                  use        => { c: text_chain }""");

        assertEquals(TypeRef.of("text_chain"), fieldOf(compiled, "text_chain", "tail").type(),
                "the tail names the declaration it sits in");
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class, () -> compile("""
                  chain => <T> { head: T  tail?: chain<T>? }
                  use   => { c: chain<text> }"""));
        assertTrue(thrown.getMessage().contains("'chain<text>' applies the recursive open record 'chain'"),
                thrown.getMessage());
    }

    /**
     * Mutual recursion through a record in place ties to the declaration being closed (§5.10.1): closing {@code
     * a_text} closes {@code b: b<text>} in place, and the {@code a<text>} it holds names {@code a_text}. The same
     * application at a slot, with nothing closing it, is refused however many declarations exist; the open records
     * alone, applied nowhere, load.
     */
    @Test
    void mutualRecursionTiesToTheDeclarationOrIsRefused() {
        String templates = """
                  a => <T> { v: T  b: b<T> }
                  b => <T> { a?: a<T> }
                """;
        TsonCompiledSchema closed = compile(templates + "  a_text => a<text>");
        TypeInline b = inPlace(closed, "a_text", "b");
        assertEquals(TypeRef.of("a_text"), fieldOf(b, "a").type());
        assertNotNull(closed.get("a_text").read(TestDocuments.document("{ v: x  b: { a: { v: y  b: {} } } }")));

        SchemaValidationException atASlot = assertThrows(SchemaValidationException.class,
                () -> compile(templates + "  a_text => a<text>\n  holder => { f: b<text> }"));
        assertTrue(atASlot.getMessage().contains("'b<text>' applies the recursive open record 'b'"),
                atASlot.getMessage());
        assertNotNull(compile(templates));
    }

    /** A mutual recursion every step of which is required describes no finite value, and is refused (§5.10.1). */
    @Test
    void anUnguardedMutualRecursionThroughARecordInPlaceIsUnproductive() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class, () -> compile("""
                  a      => <T> { v: T  b: b<T> }
                  b      => <T> { a: a<T> }
                  a_text => a<text>"""));
        assertTrue(thrown.getMessage().contains("a_text"), thrown.getMessage());
    }

    /**
     * An application whose applied parent a declaration names would close in place to a member of that declaration
     * (§5.2), and is refused, in whichever order the declarations stand and wherever the application is left.
     */
    @Test
    void anApplicationJoiningADeclaredApplicationIsRefusedInEitherOrder() {
        String templates = """
                  animal_of => <T> { a: T }
                  dog_of    => <T> animal_of<T> & { d: text }
                  kennel_of => <T> { k: dog_of<T> }
                """;
        for (String declarations : List.of(
                "  animal_text => animal_of<text>\n  kt => kennel_of<text>",
                "  kt => kennel_of<text>\n  animal_text => animal_of<text>",
                "  animal_text => animal_of<text>\n  kt => { k: dog_of<text> }",
                "  kt => { k: dog_of<text> }\n  animal_text => animal_of<text>")) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> compile(templates + declarations), declarations);
            assertTrue(thrown.getMessage().contains("IS-A 'animal_text'"), thrown.getMessage());
        }
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

    // ── Sugar inside a template body ─────────────────────────────────────
    //    A *concrete* form is the construction written in place at desugar, exactly as it is outside a
    //    template, so the template that holds it applies like any other. Only a form written over one of
    //    the declaration's own parameters waits for the application to close it.

    @Test
    void aConcreteSugarFormInsideATemplateIsInPlaceAndTheTemplateApplies() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  tmpl  => <T> { a: T  b: [order] }
                  use   => { u: tmpl<text> }""");

        assertEquals(TypeRef.of("text"), fieldOf(inPlace(compiled, "use", "u"), "a").type());
        assertNotNull(compiled.get("use")
                .read(TestDocuments.document("{ u: { a: \"x\"  b: [ { id: \"1\" } ] } }")));
    }

    /** Every sugar form, not just the array: choice, map, and a nested bracket form are all concrete in place. */
    @Test
    void everyConcreteSugarFormInATemplateBodyIsInPlace() {
        for (String field : List.of("[order]", "(order | text)", "[[order]]", "{text => order}")) {
            assertNotNull(compile("""
                      order => { id: text }
                      tmpl  => <T> { a: T  b: %s }
                      use   => { u: tmpl<text> }""".formatted(field)), field);
        }
    }

    /**
     * A form closed in an open record is the same type a directly written {@code [order]} is anywhere else in the
     * schema: two equal constructions written in place are one type (§8.2), with no entry for either.
     */
    @Test
    void aFormClosedInATemplateIsTheSameTypeADirectOneIs() {
        TsonCompiledSchema compiled = compile("""
                  order => { id: text }
                  tmpl  => <T> { a: T  b: [order] }
                  plain => { c: [order] }
                  use   => { u: tmpl<text> }""");

        assertEquals(fieldOf(compiled, "plain", "c").type(), fieldOf(inPlace(compiled, "use", "u"), "b").type());
    }

    /**
     * A container written over the declaration's own parameter is the reference it spells, and closes with the
     * application: the field ends up naming an ordinary container entry. {@code ApplicationInContainerPositionTest}
     * carries the shape of what it closes into; here the point is only that applying the template is accepted.
     */
    @Test
    void aParameterBearingSugarFormClosesWithTheApplication() {
        for (List<String> form : List.of(List.of("[T]", "[ \"x\" ]"), List.of("{text => T}", "{ k => \"x\" }"),
                List.of("nonempty<T>", "[ \"x\" ]"))) {
            String field = form.get(0);
            TsonCompiledSchema compiled = compile("""
                      nonempty => meta.array ^ { min_items?: = 1 }
                      tmpl     => <T> { a: %s }
                      use      => { u: tmpl<text> }""".formatted(field));

            assertInstanceOf(TypeInline.class, fieldOf(inPlace(compiled, "use", "u"), "a").type(),
                    () -> field + " closes to its construction in place");
            assertNotNull(compiled.get("use").read(TestDocuments.document("{ u: { a: " + form.get(1) + " } }")),
                    field);
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

    /** A declaration's own body is the construction, with a template beside it as without. */
    @Test
    void aDeclarationsOwnBodyIsTheConstruction() {
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

    /**
     * An absorbing position takes the fields and names nothing, and an ordinary position closes the same application
     * in place: neither adds an entry.
     */
    @Test
    void anAbsorbingPositionAndAnOrdinaryOneAddNoEntry() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  vip => box<text> & { extra: text }
                  holder => { b: box<text> }""");

        assertEquals(List.of(), instantiationsOf(compiled, "box"));
        assertEquals(TypeRef.of("text"), fieldOf(inPlace(compiled, "holder", "b"), "v").type());
        assertEquals(TypeRef.of("text"), fieldOf(compiled, "vip", "v").type());
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

        TypeInline use = inPlace(compiled, "holder", "u");
        assertEquals(TypeRef.of("text"), fieldOf(use, "v").type(), "absorbed from box<T> while open, closed with vip");
        assertEquals(TypeRef.of("text"), fieldOf(use, "extra").type());
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

    /** Regular recursion, by contrast, closes into its declaration -- the contrast is the point of the rule. */
    @Test
    void regularRecursionClosesWhereNonRegularDoesNot() {
        assertEquals(List.of("text_chain"), instantiationsOf(compile("""
                  chain      => <T> { head: T  tail?: chain<T>? }
                  text_chain => chain<text>
                  use        => { c: text_chain }"""), "chain"));
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
     * author wrote.
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
     * The same for a template with no sugar in its body: {@code box}'s field type is the parameter
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
