package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.numeric.IntegerSize;
import io.ltr8.tson.schema.numeric.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.unicode.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.EntryKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChoiceReader}'s untagged structural recovery (§5.4). A disjoint choice of scalars in distinct
 * classes reads an untagged value by its own §4 class -- a brace- or bracket-class variant by its delimiter, as
 * [TSON-SCHEMA] §5.4 counts it -- and a tag still works; a same-class or not-proved-disjoint choice keeps the tag
 * required. Exercised directly against a schema of
 * hand-built variant definitions (for classification) and a stub reader per variant (echoing its name), so
 * this isolates the dispatch logic from the full compile pipeline.
 */
class ChoiceUntaggedRecoveryTest {

    private final Map<String, Top> entries = new LinkedHashMap<>();

    /** Each variant's stub reader consumes its value and echoes the variant name it was dispatched to. */
    private final TsonTypeReaderResolver readers = new TsonTypeReaderResolver() {
        @Override
        public TsonTypeReader<?> resolve(String name) {
            return ctx -> {
                EventSkip.annotationsAndTypeRef(ctx);
                EventSkip.coreValue(ctx);
                return name;
            };
        }
    };

    private void variant(String name, EntryKind kind, Top body) {
        entries.put(name, body);
    }

    private TsonTypeReader<?> choice(Optional<Boolean> disjoint, String... variants) {
        List<TypeRef> refs = List.of(variants).stream().map(TypeRef::of).toList();
        Top choiceDef = new ChoiceBody(refs, disjoint);
        entries.put("contact", choiceDef);
        ValueReaderContext context = new ValueReaderContext(
                new TsonLinkedSchema(new TsonSchema("id", List.of(), entries)), readers);
        return ChoiceReader.FACTORY.create("contact", choiceDef, context);
    }

    private static Object read(TsonTypeReader<?> reader, String data) {
        return reader.read(TestDocuments.document(data));
    }

    @Test
    void recoversAnUntaggedScalarByItsBaseTypeClass() {
        variant("int32", EntryKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", EntryKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "text");

        assertEquals("int32", read(reader, "42"));       // number class -> int32
        assertEquals("text", read(reader, "\"hi\""));    // string class -> text
    }

    @Test
    void anExplicitTagStillDispatchesEvenWhenRecoveryIsAvailable() {
        variant("int32", EntryKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", EntryKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "text");

        assertEquals("int32", read(reader, "!int32 42"));
        assertEquals("text", read(reader, "!text \"hi\""));
    }

    @Test
    void anUntaggedValueOfNoVariantsClassIsAnError() {
        variant("int32", EntryKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", EntryKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "text");

        // 'true' is the boolean class; the choice has no boolean variant.
        assertThrows(ReadException.class, () -> read(reader, "true"));
    }

    @Test
    void aChoiceNotProvedDisjointKeepsTheTagRequired() {
        variant("int32", EntryKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", EntryKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.empty(), "int32", "text");

        assertThrows(ReadException.class, () -> read(reader, "42"));
        assertEquals("int32", read(reader, "!int32 42")); // tagged still works
    }

    /**
     * A record variant is brace-class, which §5.4 counts beside the scalar classes: a disjoint choice of a scalar and a
     * record recovers each by its own class, the record by its opening brace. This is what lets {@code meta.type_expr}
     * read a name and a {@code { of: … }} wrapper untagged.
     */
    @Test
    void aBraceClassVariantIsRecoveredByItsDelimiter() {
        variant("int32", EntryKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("point", EntryKind.PRODUCT, RecordBody.of(RecordBody.fieldMap()));
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "point");

        assertEquals("int32", read(reader, "42"));
        assertEquals("point", read(reader, "{ x: 1 }"));
        assertEquals("point", read(reader, "{}"));
        assertThrows(ReadException.class, () -> read(reader, "[1]"));
    }
}
