package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a {@code type_ref} value is written: an argument-free one as its bare name, which [TSON-SCHEMA] §8.1 requires
 * of canonical output and which is the only spelling a value written by name has (SPEC-FEEDBACK.md #13); an
 * application as the explicit record, which carries its arguments.
 */
class TypeRefWriteTest {

    private static String written(Object value) {
        return new TsonObjectWriter(SchemaMetaNameBinder.defaultContext()).toTson(value).replaceAll("\\s+", " ")
                .trim();
    }

    @Test
    void anArgumentFreeReferenceIsItsBareName() {
        assertEquals("text", written(TypeRef.of("text")));
        assertEquals("orders.methods.place_order", written(TypeRef.of("orders.methods.place_order")));
    }

    @Test
    void anApplicationKeepsTheExplicitRecord() {
        assertEquals("{ name: \"box\" arguments: [ !ref { name: text } ] }",
                written(new TypeRef("box", List.of(new TypeArgument.Ref(TypeRef.of("text"))))));
    }
}
