package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.numeric.IntegerType;
import io.ltr8.tson.schema.unicode.TextType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An instance of a constructor composed over another with no field added ([TSON-SCHEMA] §5.8): {@code my_list =>
 * meta.array & { min_items?: = 1 }} is a constructor IS-A {@code meta.array}, applied and constructed as its parent
 * is, its pin taking effect in the instance and refusing a construction that writes another value (§5.2), the
 * instance refinable by {@code ^} as its parent's are (§5.7). A field added, or {@code record} composed, stays a gap.
 */
class ComposedConstructorInstanceTest {

    private static final String ID = "https://example.test/composed.tn";

    private static final String HEAD = "!!import:\"" + ID + "\"\n";

    private static String schema(String body) {
        return """
                !!id:"https://example.test/composed.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  my_list => meta.array & { min_items?: = 1 }
                """ + body + "\n}\n";
    }

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaModelNameBinder.defaultContext()));
    }

    /** A Tson holding the schema {@code body} completes, which must load cleanly. */
    private static Tson loaded(String body) {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(schema(body)));
        return tson;
    }

    private static Top entry(String body, String name) {
        return tson().resolve(schema(body)).schema().entries().get(name);
    }

    private static List<Diagnostic> problems(String body) {
        return tson().validateSchema(schema(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  names => my_list<text>", "  names => !my_list { element_type: text }",
            "  names => !my_list text", "  names => !my_list { element_type: text  min_items: 1 }"})
    void eachSpellingIsTheParentsBodyWithThePinInIt(String declaration) {
        TsonLinkedSchema linked = tson().resolve(schema(declaration));
        ArrayBody names = assertInstanceOf(ArrayBody.class, linked.schema().entries().get("names"));

        assertEquals(TypeRef.of("text"), names.elementType());
        assertEquals(Optional.of(1L), names.minItems());
        assertEquals(List.of(), TypeChain.supertypesOf(names), "construction grants no supertypes (§5.5)");
        assertEquals(Optional.of("my_list"), linked.schema().factsOf("names").constructor(),
                "the instance is my_list's, the construction tagged by the head written");
    }

    @Test
    void thePinIsReadInTreeAndBindMode() {
        Tson tson = loaded("  names => my_list<text>");

        assertEquals(List.of(), tson.validate(HEAD + "!names [a b]"));
        List<Diagnostic> problems = tson.validate(HEAD + "!names []");
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("1"), problems::toString);
        assertEquals(List.of("a", "b"), tson.objectReader().withSchema(ID).readAs("[a b]", "names", List.class));
        assertThrows(ReadException.class, () -> tson.objectReader().withSchema(ID).readAs("[]", "names", List.class));
        assertThrows(ReadException.class, () -> tson.treeReader().withSchema(ID).readAs("[]", "names"));
    }

    /** In a data document too: a value of the constructor binds as its parent's body, its pin read. */
    @Test
    void aValueOfTheConstructorBindsAsItsParentsBody() {
        Tson tson = loaded("  names => my_list<text>");
        ArrayBody read = assertInstanceOf(ArrayBody.class, tson.objectReader().withSchema(ID)
                .readAs("{ element_type: text }", "my_list", Object.class));
        assertEquals(Optional.of(1L), read.minItems());
        assertThrows(ReadException.class, () -> tson.objectReader().withSchema(ID)
                .readAs("{ element_type: text  min_items: 0 }", "my_list", Object.class));
    }

    @Test
    void anApplicationAtAFieldIsTheSameConstructionInPlace() {
        Tson tson = loaded("  page => { names: my_list<text> }");

        assertEquals(List.of(), tson.validate(HEAD + "!page { names: [a] }"));
        assertEquals(1, tson.validate(HEAD + "!page { names: [] }").size());
    }

    /** §5.2: a written value at a FIXED field equals the pin, so a construction writing another is refused. */
    @Test
    void aConstructionWritingAnotherValueAtThePinIsRefused() {
        List<Diagnostic> problems = problems("  names => !my_list { element_type: text  min_items: 0 }");
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems::toString);
        assertEquals(Optional.of("/names"), problems.getFirst().schemaPointer());
        assertTrue(problems.getFirst().message().contains("'min_items' is fixed on 'my_list'"), problems::toString);
    }

    @Test
    void aDefaultIsInjectedAndOverridable() {
        String body = "  counted => meta.array & { min_items?: ~ 2 }\n  n => counted<text>\n"
                + "  m => !counted { element_type: text  min_items: 5 }";
        assertEquals(Optional.of(2L), ((ArrayBody) entry(body, "n")).minItems());
        assertEquals(Optional.of(5L), ((ArrayBody) entry(body, "m")).minItems());
    }

    /** §5.7: refined by its facets' kinds, IS-A the instance, the pin a facet fixed at construction. */
    @Test
    void theInstanceIsRefinedAsItsParentsAre() {
        String body = "  names => my_list<text>\n  few => !names ^ { max_items: 3 }";
        Tson tson = loaded(body);
        ArrayBody few = assertInstanceOf(ArrayBody.class, entry(body, "few"));
        assertEquals(List.of("names"), TypeChain.supertypesOf(few));
        assertEquals(Optional.of(1L), few.minItems());
        assertEquals(Optional.of(3L), few.maxItems());
        assertEquals(1, tson.validate(HEAD + "!few [a b c d]").size());

        List<Diagnostic> moved = problems("  names => my_list<text>\n  few => !names ^ { min_items: 2 }");
        assertEquals(1, moved.size(), moved::toString);
        assertTrue(moved.getFirst().message().contains("'min_items' is fixed on 'my_list'"), moved::toString);
    }

    @Test
    void anOpenConstructionOfItIsCompleted() {
        ArrayBody names = assertInstanceOf(ArrayBody.class,
                entry("  open => !my_list ^ { max_items: 4 }\n  names => open<text>", "names"));
        assertEquals(Optional.of(1L), names.minItems());
        assertEquals(Optional.of(4L), names.maxItems());
    }

    /** The rule recurses: a composition over a composition binds as its parent does, through an alias hop too. */
    @Test
    void aCompositionOverACompositionBindsAsItsParentDoes() {
        ArrayBody two = assertInstanceOf(ArrayBody.class,
                entry("  two => my_list & { max_items?: = 3 }\n  n => two<text>", "n"));
        assertEquals(Optional.of(1L), two.minItems());
        assertEquals(Optional.of(3L), two.maxItems());

        assertInstanceOf(ArrayBody.class, entry("  al => meta.array\n  hop => al & { ordered?: = false }\n"
                + "  n => hop<text>", "n"));
    }

    @Test
    void theOtherCollectionConstructorsAndTheAtomConstructorsBindTheSame() {
        String body = """
                  my_map   => meta.map & { min_items?: = 1 }
                  counts   => my_map<text int32>
                  my_ns    => meta.namespace & { min_items?: = 1 }
                  ops      => !my_ns { key_type: meta.identifier  value_type: text }
                  short    => unicode.text_type & { max_length?: = 3 }
                  code     => !short {}
                  even     => numeric.integer_type & { multiple_of?: = 2 }
                  evens    => !even {}""";
        Tson tson = loaded(body);
        TsonLinkedSchema linked = tson().resolve(schema(body));
        var entries = linked.schema().entries();
        assertEquals(Optional.of(1L), assertInstanceOf(MapBody.class, entries.get("counts")).minItems());
        assertEquals(Optional.of(1L), assertInstanceOf(NamespaceBody.class, entries.get("ops")).minItems());
        assertEquals(Optional.of(3L), assertInstanceOf(TextType.class, entries.get("code")).maxLength());
        assertEquals(Optional.of(2L), assertInstanceOf(IntegerType.class, entries.get("evens")).multipleOf()
                .map(Number::longValue));

        assertEquals(1, tson.validate(HEAD + "!counts {}").size());
        assertEquals(List.of(), tson.validate(HEAD + "!code abc"));
        assertEquals(1, tson.validate(HEAD + "!code abcd").size());
        assertEquals(1, tson.validate(HEAD + "!evens 3").size());
    }

    /** A resolved form's instance is read back through the entries alone: the tag names the composed constructor. */
    @Test
    void aResolvedFormReadBackBindsAsItsSourceDid() {
        Tson tson = loaded("  names => my_list<text>");
        Map<String, Object> back = ResolvedForm.readResolved(tson, """
                !schema {
                  names => !my_list { element_type: text }
                }
                """, List.of(ID));

        assertEquals(ResolvedForm.rendered(ResolvedForm.ownEntries(tson, ID).get("names")),
                ResolvedForm.rendered(back.get("names")));
    }

    /** Not this rule's: a field added, {@code record} composed, a base kind composed directly. */
    @ParameterizedTest
    @ValueSource(strings = {
            "  c => meta.map & { label?: text }\n  x => !c { key_type: text  value_type: int32 }",
            "  c => meta.record & { extension?: = FINAL }\n  x => !c { fields: { id => { type: text } } }",
            "  c => meta.record & { extra: text }\n  x => !c { fields: { id => { type: text } }  extra: hello }",
            "  c => meta.atom & {}\n  x => !c {}",
            "  c => meta.product & {}\n  x => !c {}"})
    void whatTheRuleDoesNotCoverStaysAGap(String body) {
        List<Diagnostic> problems = problems(body);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, problems.getFirst().code(), problems::toString);
        assertEquals(Optional.of("/x"), problems.getFirst().schemaPointer());
    }
}
