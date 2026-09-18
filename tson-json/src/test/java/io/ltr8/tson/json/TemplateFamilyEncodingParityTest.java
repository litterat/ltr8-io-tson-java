package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * <b>A template family base dispatches the same way in both encodings</b> ([TSON-JSON] §9.4, and {@code
 * SPEC-FEEDBACK.md} #13): a record-bodied template carries {@code extension}, so a value at a position typed
 * by it is a value of one of its instantiations, selected by the discriminators where the base is SEALED and
 * by a tag where it is ABSTRACT.
 *
 * <p><b>Why this sits beside {@link CrossEncodingParityTest} rather than inside it.</b> That test compares one
 * schema, held statically, which is what lets it be terse; a family base's two marks and the tag cases need a
 * schema each. What is shared is the comparison itself -- the code, the data pointer, the machine-readable
 * {@code expected} and the prose -- because §9.4 makes one vocabulary across both encodings an obligation, and
 * two dispatchers written separately ({@code AbstractTemplateReader} in the text stack, {@code
 * TreeTemplateAbstractReader} here) are exactly the pair that can agree by having been copied and then drift.
 *
 * <p><b>The tag an ABSTRACT base admits is a declared member's name and nothing else.</b> A pin <em>value</em>
 * is not a type however much it reads like one, and the base's own name selects nothing -- the two cases that
 * look like they should work and must not.
 */
class TemplateFamilyEncodingParityTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /** The template itself is the base, SEALED by the surviving discriminator -- dispatch by the pin. */
    private static final String SEALED = HEADER.formatted("tsealed") + """
            {
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => <T, V> { @discriminator type: text = T  value: V }
              dogpet   => pet<"dog", dog_type>
              catpet   => pet<"cat", cat_type>
              holder   => { p: pet }
            }""";

    /** The template itself is the base, ABSTRACT for want of a discriminator -- dispatch by tag. */
    private static final String ABSTRACT = HEADER.formatted("tabstract") + """
            {
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => <V> { value: V }
              dogpet   => pet<dog_type>
              catpet   => pet<cat_type>
              holder   => { p: pet }
            }""";

    /** A rule as both encodings must state it: which rule, where in the data, the constraint, and the prose. */
    private record Rule(Diagnostic.Code code, String path, String expected, String message) {

        static Rule of(Diagnostic d) {
            return new Rule(d.code(), d.path().orElse("?"), d.expected(), d.message());
        }
    }

    /** A processor that has not yet registered the schema -- resolving an already-registered one is an error. */
    private static Tson processor(String schema) {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> schema)));
    }

    private static String idOf(String schema) {
        return schema.substring(schema.indexOf('"') + 1, schema.indexOf(".tn\"") + 3);
    }

    private static List<Diagnostic> fromTson(String schema, String body) {
        Tson tson = processor(schema);
        tson.resolve(schema);
        return tson.validate("!!schema:\"" + idOf(schema) + "\"\n" + body);
    }

    private static List<Diagnostic> fromJson(String schema, String rootType, String body) {
        JsonCompiledSchema compiled = JsonSchemaCompiler.compile(processor(schema).resolve(schema));
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(body)) {
            JsonReadContext context = JsonReadContext.of(
                    new JsonStream(bytes, ProcessorPolicy.defaults(), receiver), receiver);
            context = compiled.rootDeclaration(rootType).map(context::underDeclaration).orElse(context);
            compiled.get(rootType).read(context);
        }
        return List.copyOf(problems);
    }

    /**
     * Both encodings accept the document. Vacuity is answered by the refusal cases below, which run the same
     * two readers over the same two schemas and do report -- so a reader that never ran could not pass both.
     */
    private static void bothAccept(String schema, String rootType, String tsonBody, String jsonBody) {
        assertEquals(List.of(), fromTson(schema, tsonBody), "the TSON side refused a document it should take");
        assertEquals(List.of(), fromJson(schema, rootType, jsonBody),
                "the JSON side refused a document the TSON side took");
    }

    /** Both encodings refuse it, and state the identical rule -- code, pointer, {@code expected} and prose. */
    private static void sameRule(String schema, String rootType, String tsonBody, String jsonBody) {
        List<Diagnostic> tson = fromTson(schema, tsonBody);
        assertFalse(tson.isEmpty(), "the TSON side reported nothing, so this compares nothing");
        assertEquals(tson.stream().map(Rule::of).toList(),
                fromJson(schema, rootType, jsonBody).stream().map(Rule::of).toList(),
                "the two encodings state this rule differently");
    }

    // ── SEALED: the pin selects, and no tag is written ───────────────────

    @Test
    void aSealedTemplateBaseDispatchesOnItsPinInBoth() {
        bothAccept(SEALED, "holder",
                "!holder { p: { type: \"dog\"  value: { breed: lab } } }",
                "{\"p\":{\"type\":\"dog\",\"value\":{\"breed\":\"lab\"}}}");
    }

    @Test
    void theOtherPinSelectsTheOtherMemberInBoth() {
        bothAccept(SEALED, "holder",
                "!holder { p: { type: \"cat\"  value: { indoor: true } } }",
                "{\"p\":{\"type\":\"cat\",\"value\":{\"indoor\":true}}}");
    }

    // ── ABSTRACT: a tag selects, and only a member's name is one ─────────

    @Test
    void anAbstractTemplateBaseDispatchesOnTheMemberTagInBoth() {
        bothAccept(ABSTRACT, "holder",
                "!holder { p: !dogpet { value: { breed: lab } } }",
                "{\"p\":{\"$type\":\"dogpet\",\"value\":{\"breed\":\"lab\"}}}");
    }

    /**
     * {@code dog} is a pin <em>value</em> in the SEALED spelling of this family and is no type in either. A
     * tag naming it selects nothing, and both encodings must say so the same way rather than one of them
     * resolving it for looking like a member.
     */
    @Test
    void aTagNamingAPinValueRatherThanAMemberIsOneRuleInBoth() {
        sameRule(ABSTRACT, "holder",
                "!holder { p: !dog { value: { breed: lab } } }",
                "{\"p\":{\"$type\":\"dog\",\"value\":{\"breed\":\"lab\"}}}");
    }

    /** ABSTRACT has no direct instances, so the tag is not optional the way a SEALED base's is. */
    @Test
    void anAbstractTemplateBaseRequiresItsTagInBoth() {
        sameRule(ABSTRACT, "holder",
                "!holder { p: { value: { breed: lab } } }",
                "{\"p\":{\"value\":{\"breed\":\"lab\"}}}");
    }

    /** And the base's own name selects nothing: it is the family, not a member of it. */
    @Test
    void aTagNamingTheTemplateBaseItselfIsOneRuleInBoth() {
        sameRule(ABSTRACT, "holder",
                "!holder { p: !pet { value: { breed: lab } } }",
                "{\"p\":{\"$type\":\"pet\",\"value\":{\"breed\":\"lab\"}}}");
    }
}
