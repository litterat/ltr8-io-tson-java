package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonCompiledSchemaRegistry;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field's default or pin is held as written while the readers are built, and decoded through its type once
 * every reader of the schema exists ([TSON-SCHEMA] §5.2; SPEC-FEEDBACK.md #4 rule 3) -- so building a record's
 * reader never reads through the reader of a pin's type, and a pin whose type is still being built holds
 * nothing back. Every pin is still judged before the schema is handed out (rule 6).
 */
class HeldFieldValueTest {

    /**
     * {@code wrap} is built first and reaches {@code point} through {@code back}, so {@code point} is built while
     * {@code wrap} is still on the build stack and its field {@code n} gets the stand-in for an unfinished reader.
     * Decoding {@code n}'s default while {@code point} is built would read through that stand-in and fail.
     *
     * <p>Linking confines a pin to an atom- or enum-typed field, whose reader reaches no other entry, so no
     * linked schema puts a pin's type on the build stack today; this schema is compiled unlinked because a
     * record with one required field (§5.6's positional form) is the type a token can fill whose reader does.
     */
    private static TsonLinkedSchema pinTypedByAnEntryStillBuilding() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("int32", new IntegerType(new IntegerSize(32, true)));
        entries.put("wrap", RecordBody.of(RecordBody.fieldMap(
                Map.entry("v", RecordField.required(TypeRef.of("int32"))),
                Map.entry("back", RecordField.optional(TypeRef.of("point"))))));
        entries.put("point", RecordBody.of(RecordBody.fieldMap(
                Map.entry("n", RecordField.defaulted(TypeRef.of("wrap"), new Token("1", Token.Form.UNQUOTED))),
                Map.entry("m", RecordField.fixed(TypeRef.of("wrap"), new Token("2", Token.Form.UNQUOTED))))));
        return new TsonLinkedSchema(new TsonSchema("test-schema", List.of(), entries));
    }

    @Test
    void aRecordWhosePinsTypeIsStillBeingBuiltCompilesAndInjectsThePin() {
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(pinTypedByAnEntryStillBuilding(),
                ValueReaderFactoryRegistry.tree());

        assertTrue(RecordAbstractReader.unwrapped(compiled.get("point")) instanceof RecordTreeReader,
                () -> "'point' did not compile: " + compiled.get("point"));
        TsonValue point = (TsonValue) compiled.get("point").read(TestDocuments.document("{}"));
        assertEquals(OptionalInt.of(1), point.get("n").get("v").asInt());
        assertEquals(OptionalInt.of(2), point.get("m").get("v").asInt());
    }

    private static final String SCHEMA_ID = "https://example.test/held-field-value.tn";

    private static TsonCompiledSchema load(String body) {
        String schema = """
                !!id:"https://example.test/held-field-value.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema
                """ + body;
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, SCHEMA_ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry metas =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(metas).get(SCHEMA_ID);
    }

    @Test
    void aPinThatIsNotAValueOfItsTypeStillFailsTheLoad() {
        SchemaValidationException pin = assertThrows(SchemaValidationException.class,
                () -> load("{ p => { n: int32 = abc } }"));
        assertTrue(pin.getMessage().contains("'p': field 'n' is declared 'int32', but its fixed value abc is not "
                + "a value of that type"), pin::getMessage);

        SchemaValidationException defaulted = assertThrows(SchemaValidationException.class,
                () -> load("{ p => { n?: int8 ~ 300 } }"));
        assertTrue(defaulted.getMessage().contains("'p': field 'n' is declared 'int8', but its default 300 is not "
                + "a value of that type"), defaulted::getMessage);
    }

    @Test
    void aValidPinAndDefaultLoadAndInject() {
        TsonCompiledSchema compiled = load("{ p => { n?: int32 = 4  d?: text ~ \"x\" } }");
        TsonValue p = (TsonValue) compiled.get("p").read(TestDocuments.document("{}"));
        assertEquals(OptionalInt.of(4), p.get("n").asInt());
        assertEquals(Optional.of("x"), p.get("d").asString());
    }
}
