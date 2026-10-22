package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.4's {@code (A | B)} sugar, through {@code SchemaDesugarer} to a resolved {@code ChoiceBody}. §5.6's
 * desugaring table gives one target -- {@code !choice { variants: [A B] } } -- and the position decides
 * whether that construction becomes the declaration itself or an injected one referred to by name.
 *
 * <p>Where {@code ChoiceConstructionResolutionTest} covers the construction written out by hand, these cover
 * the sugar an author actually writes, which is what gives {@code ChoiceReader} and {@code
 * ChoiceDisjointness} their input.
 */
class ChoiceSugarResolutionTest {

    /** Governed by meta-kernel, which declares the {@code choice} constructor; its compiled form bootstraps on demand. */
    private static TsonSchema resolve(String body) {
        String document = """
                !!id:"https://example.test/choice-sugar.tn"
                !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
                {
                %s
                }
                """.formatted(body);
        SchemaResolver resolver = new SchemaResolver(new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext()));
        return resolver.resolveSchema(new TsonSchemaParser(document).parseSchemaDocument());
    }

    private static List<String> variantNames(Top definition) {
        return assertInstanceOf(ChoiceBody.class, definition).variants().stream().map(TypeRef::name).toList();
    }

    /** The one entry whose name starts with the given prefix -- an injected declaration carries a derived hash suffix. */
    private static Map.Entry<String, Top> injected(TsonSchema schema, String prefix) {
        List<Map.Entry<String, Top>> matches = schema.entries().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix)).toList();
        assertEquals(1, matches.size(), () -> "expected exactly one injected '" + prefix + "*' entry, got "
                + matches.stream().map(Map.Entry::getKey).toList());
        return matches.getFirst();
    }

    /**
     * At declaration position the sugar <em>is</em> the construction, so the entry itself carries the
     * {@code ChoiceBody} -- not a REFERENCE to an injected one. It resolves as the ordinary construction of
     * {@code choice} it desugars to: its tag is the constructor, SUM kind comes from that
     * constructor, and {@code supertypes} stays empty because §5.5 transfers the target's kind and nothing
     * else -- which is also all §8.1's own Choice row claims.
     */
    @Test
    void aDeclaredChoiceIsASumEntryCarryingAChoiceBody() {
        TsonSchema schema = resolve("  contact => (text | integer)");

        Top contact = schema.entries().get("contact");
        assertEquals(TypeKind.SUM, TypeKind.of(contact, n -> true));
        assertEquals(List.of("text", "integer"), variantNames(contact));
        assertEquals(java.util.Optional.of("choice"), schema.constructorOf("contact"));
        assertTrue(TypeChain.supertypesOf(contact).isEmpty(), () -> "expected no supertypes, got "
                + TypeChain.supertypesOf(contact));
    }

    /** More than two variants: §5.4 sets a floor of two, not a ceiling, and the array field takes them positionally. */
    @Test
    void aDeclaredChoiceKeepsEveryVariantInOrder() {
        TsonSchema schema = resolve("  scalar => (text | integer | boolean)");

        assertEquals(List.of("text", "integer", "boolean"), variantNames(schema.entries().get("scalar")));
    }

    /**
     * At a field position the choice is hoisted into its own declaration and the field refers to it by name.
     * §8.2 requires it: every application materialises an entry, and a use site holds a bare reference to
     * it -- nothing downstream reads a {@code type_ref}'s arguments, so a structural form would resolve and
     * then have nothing to compile against.
     */
    @Test
    void anInlineChoiceIsHoistedAndReferredToByName() {
        TsonSchema schema = resolve("  person => { contact: (text | integer) }");

        Map.Entry<String, Top> choice = injected(schema, "choice_text_integer_");
        assertEquals(List.of("text", "integer"), variantNames(choice.getValue()));

        RecordBody person = assertInstanceOf(RecordBody.class, schema.entries().get("person"));
        assertEquals(choice.getKey(), person.fields().sequencedValues().getFirst().type().name());
    }

    /**
     * The walk is bottom-up, so a variant that is itself a sugar form is already a plain name by the time the
     * choice around it is built -- {@code [text]} becomes its own injected array entry, and the choice refers
     * to that.
     */
    @Test
    void aVariantThatIsItselfASugarFormIsHoistedFirst() {
        TsonSchema schema = resolve("  contact => ([text] | integer)");

        String array = injected(schema, "array_text_").getKey();
        assertEquals(List.of(array, "integer"), variantNames(schema.entries().get("contact")));
    }

    /**
     * The injected name is derived from the variants, so two structurally identical inline choices collapse
     * to one declaration (§8.2's structural-equality rule) rather than injecting a near-duplicate per use.
     */
    @Test
    void twoIdenticalInlineChoicesCollapseToOneDeclaration() {
        TsonSchema schema = resolve("""
                  person => { home: (text | integer)  work: (text | integer) }""");

        String choice = injected(schema, "choice_text_integer_").getKey();
        RecordBody person = assertInstanceOf(RecordBody.class, schema.entries().get("person"));
        assertEquals(List.of(choice, choice), person.fields().values().stream().map(field -> field.type().name()).toList());
    }
}
