package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code $type} spelling an <b>alias</b>. [TSON-SCHEMA] §7.2 compares "after reference flattening of
 * <b>both</b>", so an alias and its target are one type at either end -- and §9.4 makes that one rule across
 * the encodings rather than two that happen to agree, so what {@code !s_of} does in TSON text
 * {@code "$type": "s_of"} does here.
 *
 * <p>Every position that matches a written name against a set is covered: a plain record, an abstract base, a
 * sealed base's deeper tag, and a choice's variants. The alias is admitted rather than rewritten to its
 * target, so the reader that runs is the one named for the entry the author wrote.
 */
class JsonAliasTagReadTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/alias-tag.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              base   => { tag: text }
              sub    => base & { extra: text }
              s_of   => sub
              b_of   => base

              shape  => @abstract { area: int32 }
              square => shape & { side: int32 }
              sq_of  => square

              pet    => @sealed { @discriminator pet_type: text }
              dog    => pet & { pet_type: = "dog"  bark: text }
              dog_of => dog

              note   => { body: text }
              count  => { n: int32 }
              n_of   => note
              either => ( note | count )
            }
            """;

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(Tson.standard().resolve(SCHEMA));

    private record Read(JsonValue value, List<Diagnostic> problems) {

        JsonValue accepted() {
            assertEquals(List.of(), problems.stream().map(Diagnostic::message).toList(), "expected a clean read");
            return value;
        }

        Diagnostic refusal() {
            assertEquals(1, problems.size(), () -> "expected exactly one problem, got " + problems);
            return problems.getFirst();
        }
    }

    private static Read read(String typeName, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration(typeName).map(ctx::underDeclaration).orElse(ctx);
            return new Read((JsonValue) COMPILED.get(typeName).read(ctx), List.copyOf(problems));
        }
    }

    @Test
    void anAliasOfASubtypeSelectsIt() {
        assertEquals("""
                {"tag":"x","extra":"y"}""",
                read("base", """
                        {"$type":"s_of","tag":"x","extra":"y"}""").accepted().toString());
    }

    /** The alias reaches the subtype's own reader, so the subtype's field set is what is checked. */
    @Test
    void theAliasReachesTheSubtypesOwnReader() {
        Diagnostic refused = read("base", """
                {"$type":"s_of","tag":"x"}""").refusal();

        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refused.code());
        assertTrue(refused.message().contains("extra"), refused.message());
    }

    /** §8.1's redundant tag, spelled as an alias of the position's own type. */
    @Test
    void anAliasOfThePositionsOwnTypeIsARedundantTag() {
        read("base", """
                {"$type":"b_of","tag":"x"}""").accepted();
    }

    /** An abstract base requires a tag, and an alias of a subtype is one. */
    @Test
    void anAliasSatisfiesAnAbstractPositionsRequiredTag() {
        read("shape", """
                {"$type":"sq_of","area":4,"side":2}""").accepted();
    }

    /**
     * A sealed position places the value by its discriminator and then checks any tag against the member it
     * selected (§6.1.5). An alias of that member agrees with the dispatch rather than contradicting it.
     */
    @Test
    void anAliasOfTheDispatchedMemberAgreesWithTheDiscriminatorAtASealedPosition() {
        read("pet", """
                {"pet_type":"dog","bark":"woof","$type":"dog_of"}""").accepted();
    }

    /** §5.4 variant membership takes the same flattening: an alias of a variant names that variant. */
    @Test
    void anAliasOfAVariantIsAdmittedAtAChoicePosition() {
        read("either", """
                {"$type":"n_of","body":"x"}""").accepted();
    }

    /** Flattening decides what a name means, not whether it is admitted: an unrelated alias still is not. */
    @Test
    void anAliasOfSomethingUnrelatedIsStillRefused() {
        Diagnostic refused = read("base", """
                {"$type":"n_of","body":"x"}""").refusal();

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
    }
}
