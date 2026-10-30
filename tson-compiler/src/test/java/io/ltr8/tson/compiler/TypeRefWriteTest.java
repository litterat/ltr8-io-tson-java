package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a reference is written: an argument-free one as its bare name, which [TSON-SCHEMA] §8.1 requires of canonical
 * output and which is the only spelling a value written by name has ([TSON-SCHEMA] §5.12); an application as its
 * canonical text, unquoted, the data grammar's reference (SPEC-FEEDBACK.md #12).
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
    void anApplicationIsItsUnquotedText() {
        assertEquals("box<text>", written(new TypeRef("box", List.of(new TypeArgument.Ref(TypeRef.of("text"))))));
        assertEquals("map<text 3 \"a b\">", written(new TypeRef("map", List.of(
                new TypeArgument.Ref(TypeRef.of("text")), new TypeArgument.Value(new Token("3", Token.Form.UNQUOTED)),
                new TypeArgument.Value(new Token("a b", Token.Form.SINGLE_LINE_QUOTED))))));
    }
}
