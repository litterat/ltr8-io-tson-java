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
 * <b>A sealed family whose members a subtype-template factory mints reads alike in both encodings</b>
 * ([TSON-JSON] §9.4): the pin a factory writes from a type parameter ({@code kind: = T}) places a value
 * exactly as a hand-written pin does, and a payload nested under a single field is read the same way in both.
 *
 * <p><b>The shape is distinct from the two parity tests beside it, which is why it has its own.</b> {@link
 * CrossEncodingParityTest}'s factory sits over an {@code @abstract} base, so it dispatches by tag and its
 * payload is flat; {@link TemplateFamilyEncodingParityTest}'s base is the template itself. Here the base is a
 * closed {@code @sealed} record and every member is minted, which is what puts [TSON-SCHEMA] §5.7's fixation
 * on the dispatch path: {@code kind: = T} is an ordinary REQUIRED_FIXED field of the closed member by the time
 * either encoding reads it, and {@code FamilySelectors} states the selector on the entry, so neither stack
 * parses held text to find what selects.
 *
 * <p>The comparison is the strongest one available -- code, data pointer, {@code expected} and the prose --
 * because §9.4 makes one diagnostic vocabulary across both encodings an obligation rather than a tidiness.
 */
class SealedFactoryEncodingParityTest {

    private static final String ID = "https://example.test/sealed-factory.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/sealed-factory.tn"
            !!meta:"%s"
            !!import:"%s"
            {
              msg    => @sealed { @discriminator kind: text }
              msg_of => <T, V> msg & { kind: = T  body: V }

              ping_body => { seq: int32 }
              pong_body => { seq: int32  latency: int32 }

              ping => msg_of<"ping", ping_body>
              pong => msg_of<"pong", [pong_body]>

              channel => { m: msg }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /** A rule as both encodings must state it: which rule, where in the data, the constraint, and the prose. */
    private record Rule(Diagnostic.Code code, String path, String expected, String message) {

        static Rule of(Diagnostic d) {
            return new Rule(d.code(), d.path().orElse("?"), d.expected(), d.message());
        }
    }

    /** A processor that has not yet registered the schema -- resolving an already-registered one is an error. */
    private static Tson processor() {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));
    }

    private static List<Diagnostic> fromTson(String body) {
        Tson tson = processor();
        tson.resolve(SCHEMA);
        return tson.validate("!!schema:\"" + ID + "\"\n" + body);
    }

    private static List<Diagnostic> fromJson(String rootType, String body) {
        JsonCompiledSchema compiled = JsonSchemaCompiler.compile(processor().resolve(SCHEMA));
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
     * two readers over the same schema and do report -- so a reader that never ran could not pass both.
     */
    private static void bothAccept(String rootType, String tsonBody, String jsonBody) {
        assertEquals(List.of(), fromTson(tsonBody), "the TSON side refused a document it should take");
        assertEquals(List.of(), fromJson(rootType, jsonBody),
                "the JSON side refused a document the TSON side took");
    }

    /** Both encodings refuse it, and state the identical rule -- code, pointer, {@code expected} and prose. */
    private static void sameRule(String rootType, String tsonBody, String jsonBody) {
        List<Diagnostic> tson = fromTson(tsonBody);
        assertFalse(tson.isEmpty(), "the TSON side reported nothing, so this compares nothing");
        assertEquals(tson.stream().map(Rule::of).toList(),
                fromJson(rootType, jsonBody).stream().map(Rule::of).toList(),
                "the two encodings state this rule differently");
    }

    /** The pin the factory wrote from {@code T} selects the member, with no tag in either encoding. */
    @Test
    void aFactoryMintedMemberDispatchesOnItsPinInBoth() {
        bothAccept("channel",
                "!channel { m: { kind: ping  body: { seq: 1 } } }",
                "{\"m\":{\"kind\":\"ping\",\"body\":{\"seq\":1}}}");
    }

    /** The other member, whose payload is a container -- minted over a synthetic of its own. */
    @Test
    void theOtherPinSelectsTheContainerPayloadMemberInBoth() {
        bothAccept("channel",
                "!channel { m: { kind: pong  body: [ { seq: 1  latency: 7 } ] } }",
                "{\"m\":{\"kind\":\"pong\",\"body\":[{\"seq\":1,\"latency\":7}]}}");
    }

    /** A pin no member states is one rule in both -- so the acceptance above is a dispatch, not a pass-through. */
    @Test
    void aPinNoMemberStatesIsOneRuleInBoth() {
        sameRule("channel",
                "!channel { m: { kind: nope  body: { seq: 1 } } }",
                "{\"m\":{\"kind\":\"nope\",\"body\":{\"seq\":1}}}");
    }

    /** And the selected member validates in full: a field its payload requires is still missing. */
    @Test
    void theSelectedMemberThenValidatesInFullInBoth() {
        sameRule("channel",
                "!channel { m: { kind: ping  body: { } } }",
                "{\"m\":{\"kind\":\"ping\",\"body\":{}}}");
    }
}
