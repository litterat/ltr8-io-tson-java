package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledVocabularyRegistry;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.Top;
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

    /** Importing the kernel, which declares the {@code choice} constructor; its compiled form bootstraps on demand. */
    private static TsonSchema resolve(String body) {
        String document = """
                !!id:"https://example.test/choice-sugar.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                %s
                }
                """.formatted(body);
        SchemaResolver resolver = new SchemaResolver(new TsonCompiledVocabularyRegistry(
                SchemaModelNameBinder.defaultContext(), TsonBundledSchemas::fetch));
        return resolver.resolveSchema(new TsonSchemaParser(document).parseSchemaDocument());
    }

    private static List<String> variantNames(Top definition) {
        return assertInstanceOf(ChoiceBody.class, definition).variants().stream()
                .map(io.ltr8.tson.schema.meta.TypeExpr::text).toList();
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
     * {@code ChoiceBody} -- not an alias to an injected one. It resolves as the ordinary construction of
     * {@code choice} it desugars to: its tag is the constructor, SUM kind comes from that
     * constructor, and {@code supertypes} stays empty because §5.5 transfers the target's kind and nothing
     * else -- which is also all §8.1's own Choice row claims.
     */
    @Test
    void aDeclaredChoiceIsASumEntryCarryingAChoiceBody() {
        TsonSchema schema = resolve("  contact => (text | integer)");

        Top contact = schema.entries().get("contact");
        assertEquals(EntryKind.SUM, EntryKind.of(contact, n -> true));
        assertEquals(List.of("text", "integer"), variantNames(contact));
        assertEquals(java.util.Optional.of("meta.choice"), schema.constructorOf("contact"));
        assertTrue(TypeChain.supertypesOf(contact).isEmpty(), () -> "expected no supertypes, got "
                + TypeChain.supertypesOf(contact));
    }

    /** More than two variants: §5.4 sets a floor of two, not a ceiling, and the array field takes them positionally. */
    @Test
    void aDeclaredChoiceKeepsEveryVariantInOrder() {
        TsonSchema schema = resolve("  scalar => (text | integer | boolean)");

        assertEquals(List.of("text", "integer", "boolean"), variantNames(schema.entries().get("scalar")));
    }

}
