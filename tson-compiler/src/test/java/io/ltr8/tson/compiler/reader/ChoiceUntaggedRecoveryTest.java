package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.ForeignSchemas;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChoiceReader}'s untagged structural recovery (§5.4). A disjoint choice of scalars in distinct
 * base-type classes reads an untagged value by its own §4 class; a tag still works; a same-class or
 * non-scalar or not-proved-disjoint choice keeps the tag required. Exercised directly against a schema of
 * hand-built variant definitions (for classification) and a stub reader per variant (echoing its name), so
 * this isolates the dispatch logic from the full compile pipeline.
 */
class ChoiceUntaggedRecoveryTest {

    private final Map<String, TypeDefinition> entries = new LinkedHashMap<>();

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

    private void variant(String name, TypeKind kind, Top body) {
        entries.put(name, new TypeDefinition(Optional.empty(), kind, List.of(), false,
                List.of(), List.of(), Optional.empty(), body));
    }

    private TsonTypeReader<?> choice(Optional<Boolean> disjoint, String... variants) {
        List<TypeRef> refs = List.of(variants).stream().map(TypeRef::of).toList();
        TypeDefinition choiceDef = new TypeDefinition(Optional.empty(), TypeKind.SUM, List.of(), false,
                List.of(), List.of(), disjoint, new ChoiceBody(refs));
        entries.put("contact", choiceDef);
        ValueReaderContext context = new ValueReaderContext(
                new TsonLinkedSchema(new TsonSchema("id", "meta", List.of(), entries)), readers,
                ForeignSchemas.none());
        return ChoiceReader.FACTORY.create("contact", choiceDef, context);
    }

    private static Object read(TsonTypeReader<?> reader, String data) {
        return reader.read(TestDocuments.document(data));
    }

    @Test
    void recoversAnUntaggedScalarByItsBaseTypeClass() {
        variant("int32", TypeKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", TypeKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "text");

        assertEquals("int32", read(reader, "42"));       // number class -> int32
        assertEquals("text", read(reader, "\"hi\""));    // string class -> text
    }

    @Test
    void anExplicitTagStillDispatchesEvenWhenRecoveryIsAvailable() {
        variant("int32", TypeKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", TypeKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "text");

        assertEquals("int32", read(reader, "!int32 42"));
        assertEquals("text", read(reader, "!text \"hi\""));
    }

    @Test
    void anUntaggedValueOfNoVariantsClassIsAnError() {
        variant("int32", TypeKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", TypeKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "text");

        // 'true' is the boolean class; the choice has no boolean variant.
        assertThrows(TsonReadException.class, () -> read(reader, "true"));
    }

    @Test
    void aChoiceNotProvedDisjointKeepsTheTagRequired() {
        variant("int32", TypeKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("text", TypeKind.ATOM, TextType.UNCONSTRAINED);
        TsonTypeReader<?> reader = choice(Optional.empty(), "int32", "text");

        assertThrows(TsonReadException.class, () -> read(reader, "42"));
        assertEquals("int32", read(reader, "!int32 42")); // tagged still works
    }

    @Test
    void aNonScalarVariantKeepsTheTagRequired() {
        variant("int32", TypeKind.ATOM, new IntegerType(new IntegerSize(32, true)));
        variant("point", TypeKind.PRODUCT, new RecordBody(List.of(), List.of(), List.of()));
        // Disjoint (different kinds), but a record isn't a base-type-class scalar -- no structural recovery here.
        TsonTypeReader<?> reader = choice(Optional.of(true), "int32", "point");

        assertThrows(TsonReadException.class, () -> read(reader, "42"));
    }
}
