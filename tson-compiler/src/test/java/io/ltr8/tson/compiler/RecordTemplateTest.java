package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.10 materialisation for <b>record</b> templates -- the form whose parameters occupy field types and
 * field values. Applying one closes it: the arguments are substituted into the template's recorded open
 * form and the application is replaced by a reference to the entry that results
 * ({@code TemplateMaterialiser}).
 *
 * <p>A template whose body writes a §5.3 container sugar form is out of scope and still fails at the
 * application site -- see {@code ContainerSugarEndToEndTest}.
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
     * <b>Non-regular recursion is caught rather than run.</b> {@code weird<text>} closes to
     * {@code weird<box<text>>}, then {@code weird<box<box<text>>>}, … -- the argument grows every level, so
     * every instantiation is distinct, the knot-tying memo never fires, and there is no finite set of types
     * to build. Unguarded this is a {@link StackOverflowError}, which is neither a diagnosis nor something
     * the exception policy can classify; guarded it is the author's error, reported at the application.
     *
     * <p>Reachable without any container sugar, which is why it belongs here and not with the sugar forms:
     * the argument grows through an ordinary second template.
     */
    @Test
    void nonRegularRecursionIsReportedRatherThanOverflowingTheStack() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          box   => <T> { v: T }
                          weird => <T> { next: weird<box<T>>? }
                          use   => { w: weird<text> }"""));

        assertTrue(thrown.getMessage().contains("'weird<...>' does not close"),
                "names the template the author wrote, not whichever link tipped the depth over: "
                        + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("arguments are growing"), thrown.getMessage());
    }

    /** Regular recursion, by contrast, closes at one entry -- the contrast is the point of the guard. */
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
}
