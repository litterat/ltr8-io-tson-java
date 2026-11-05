package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sibling reference, {@code kind!} ([TSON-SCHEMA] §5.2, proposed): its text, its identity beside a type reference's,
 * and the field-type consumers that do not read one yet refusing it as this library's gap.
 */
class SiblingRefTest {

    @Test
    void itsTextIsTheFieldAndTheMark() {
        assertEquals("kind!", SiblingRef.of("kind").text());
        assertEquals("kind", SiblingRef.of("kind").field());
    }

    @Test
    void identityIsTheFieldAloneAndNeverATypeReferences() {
        SiblingRef annotated = SiblingRef.of("kind")
                .withAnnotations(Annotations.of(List.of(Annotation.of("doc", "the kind's payload"))));
        assertEquals(SiblingRef.of("kind"), annotated);
        assertEquals(SiblingRef.of("kind").hashCode(), annotated.hashCode());
        assertTrue(annotated.annotations().has("doc"));
        assertNotEquals(SiblingRef.of("kind"), SiblingRef.of("type"));
        assertNotEquals(TypeRef.of("kind"), SiblingRef.of("kind"));
        assertNotEquals(SiblingRef.of("kind"), TypeRef.of("kind"));
    }

    @Test
    void aSiblingNamesAField() {
        assertThrows(IllegalArgumentException.class, () -> SiblingRef.of(""));
        assertThrows(NullPointerException.class, () -> SiblingRef.of(null));
        assertEquals(Annotations.empty(), new SiblingRef("kind", null).annotations());
    }

    @Test
    void typeRefOfBuildsANameOrAnApplicationAndNeverASibling() {
        assertInstanceOf(NameRef.class, TypeRef.of("kind"));
        assertInstanceOf(NameRef.class, TypeRef.of("kind", List.of()));
        assertInstanceOf(ApplicationRef.class,
                TypeRef.of("box", List.of(new TypeArgument.Ref(TypeRef.of("text")))));
        Reference written = TypeRef.of("kind!");
        assertInstanceOf(NameRef.class, written, "a name spelled with the mark is still a name the factory was given");
    }

    @Test
    void aFieldTypedBySiblingIsKeptAndItsTypeReferenceIsAGap() {
        RecordField field = RecordField.required(SiblingRef.of("kind"));
        assertEquals(SiblingRef.of("kind"), field.type());
        UnsupportedOperationException gap = assertThrows(UnsupportedOperationException.class, field::typeRef);
        assertTrue(gap.getMessage().contains("'kind!'"), gap.getMessage());
        assertSame(field.type(), field.withAnnotations(Annotations.empty()).type());
        assertEquals(TypeRef.of("text"), RecordField.required(TypeRef.of("text")).typeRef());
    }

    @Test
    void aSiblingWritesNoParameterAndNoTypeReferenceOfATemplate() {
        RecordTemplateField payload = new RecordTemplateField(Optional.of(SiblingRef.of("kind")), Optional.empty(),
                false, false, FieldRole.FREE, Optional.empty(), Optional.empty(), null, null);
        assertEquals(Optional.empty(), payload.parameterInType(List.of("kind", "T")));
        assertEquals(RecordField.required(SiblingRef.of("kind")), payload.closed());
    }
}
