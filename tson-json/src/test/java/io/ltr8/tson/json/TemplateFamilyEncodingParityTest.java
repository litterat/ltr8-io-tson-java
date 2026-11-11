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
 * <b>A family declared through a template dispatches the same way in both encodings</b> ([TSON-JSON] §9.4, and
 * [TSON-SCHEMA] §5.10). A template is no type, so a family's base is a record: an abstract record a template
 * composes, its members reaching it through the template's written parent, or an abstract template's application,
 * which is an abstract record carrying the template's selector. A value at the base's position is a value of one
 * of its members, selected by the discriminators where the base states them and by a tag where it states none.
 *
 * <p><b>Why this sits beside {@link CrossEncodingParityTest} rather than inside it.</b> That test compares one
 * schema, held statically, which is what lets it be terse; the family's shapes and the tag cases need a schema
 * each. What is shared is the comparison itself -- the code, the data pointer, the machine-readable {@code
 * expected} and the prose -- because §9.4 makes one vocabulary across both encodings an obligation, and two
 * dispatchers written separately ({@code RecordTagDispatchReader} in the text stack, {@code DispatchTagReader}
 * here) are exactly the pair that can agree by having been copied and then drift.
 *
 * <p><b>The tag an ABSTRACT base admits is a declared member's name and nothing else.</b> A pin <em>value</em>
 * is not a type however much it reads like one, and the base's own name selects nothing -- the two cases that
 * look like they should work and must not.
 */
class TemplateFamilyEncodingParityTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!import:"%s"
            !!import:"%s"
            !schema
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /** An abstract record states the selector, a template composes it, and each member pins it -- by the pin. */
    private static final String SEALED = HEADER.formatted("tsealed") + """
            {
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => abstract { type: text =? }
              pet_of   => <V> pet & { value: V }
              dogpet   => pet_of<dog_type> & { type?: = "dog" }
              catpet   => pet_of<cat_type> & { type?: = "cat" }
              holder   => { p: pet }
            }""";

    /** The base states no selector, and each member declares an application of the template -- by tag. */
    private static final String ABSTRACT = HEADER.formatted("tabstract") + """
            {
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => abstract { }
              pet_of   => <V> pet & { value: V }
              dogpet   => pet_of<dog_type>
              catpet   => pet_of<cat_type>
              holder   => { p: pet }
            }""";

    /** The base is an abstract template's application, carrying its selector -- by the pin. */
    private static final String APPLIED = HEADER.formatted("tapplied") + """
            {
              pet      => abstract <V> { type: text =?  value: V }
              dogpet   => pet<text> & { type?: = "dog"  breed: text }
              catpet   => pet<text> & { type?: = "cat"  indoor: boolean }
              pet_text => pet<text>
              holder   => { p: pet_text }
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
        return tson.validate("!!import:\"" + idOf(schema) + "\"\n" + body);
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
    void aSealedBaseThroughATemplateDispatchesOnItsPinInBoth() {
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

    /** The base is an abstract template's application, and its members are the declarations composing it. */
    @Test
    void anAbstractTemplatesApplicationDispatchesOnItsPinInBoth() {
        bothAccept(APPLIED, "holder",
                "!holder { p: { type: \"cat\"  value: x  indoor: true } }",
                "{\"p\":{\"type\":\"cat\",\"value\":\"x\",\"indoor\":true}}");
    }

    /** A pin no member states selects nothing at the application's position, in both encodings alike. */
    @Test
    void aPinNoMemberOfTheApplicationStatesIsOneRuleInBoth() {
        sameRule(APPLIED, "holder",
                "!holder { p: { type: \"bird\"  value: x } }",
                "{\"p\":{\"type\":\"bird\",\"value\":\"x\"}}");
    }

    // ── ABSTRACT: a tag selects, and only a member's name is one ─────────

    @Test
    void anAbstractBaseThroughATemplateDispatchesOnTheMemberTagInBoth() {
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
    void anAbstractBaseThroughATemplateRequiresItsTagInBoth() {
        sameRule(ABSTRACT, "holder",
                "!holder { p: { value: { breed: lab } } }",
                "{\"p\":{\"value\":{\"breed\":\"lab\"}}}");
    }

    /** And the base's own name selects nothing: it is the family, not a member of it. */
    @Test
    void aTagNamingTheBaseItselfIsOneRuleInBoth() {
        sameRule(ABSTRACT, "holder",
                "!holder { p: !pet { value: { breed: lab } } }",
                "{\"p\":{\"$type\":\"pet\",\"value\":{\"breed\":\"lab\"}}}");
    }
}
