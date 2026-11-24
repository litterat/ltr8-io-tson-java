package io.ltr8.tson;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.extern;
import static io.ltr8.tson.ExternWorld.joined;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an extern names ([TSON-SCHEMA] §5.13): a type a value can have, declared anywhere in its schema's closure, and
 * nothing else -- each refusal against the extern, naming the foreign entry and what it is.
 */
class ExternNamingTest {

    private static final String LIB = "https://example.test/lib.tn";

    private final ExternWorld world = new ExternWorld().schema(LIB, """
            rec       => { a: text }
            pet       => abstract { name: text }
            count     => !integer ^ { min: 0 }
            colour    => !enum [red green]
            either    => (text | int32)
            names     => [text]
            lookup    => { text => int32 }
            pair      => [text, int32]
            rec_alias => rec
            box       => <T> { v: T }
            text_box  => box<text>
            box_alias => box
            geo       => !schema { address => { street: text } }
            my_list   => meta.array
            a_rec     => !rec { a: "x" }
            ext_card  => !meta.extern { schema: "https://example.test/order-1.tn"  type: card }
            """, ORDER_1);

    @ParameterizedTest
    @ValueSource(strings = {"rec", "pet", "count", "colour", "either", "names", "lookup", "pair", "rec_alias",
            "text_box", "geo.address", "ext_card", "order", "int32", "text"})
    void aTypeAValueCanHaveAnywhereInTheClosureIsNamed(String type) {
        assertEquals(List.of(), world.validate(extern("x", LIB, type) + "use => { f: x }"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "box|is a parametric record, which denotes no type until it is applied (§5.10)",
            "box_alias|is an alias of 'box', which is a parametric record, which denotes no type until it is applied "
                    + "(§5.10)",
            "set|is an alias of 'meta.set_type', which is an incomplete entry, which is no type until it is completed",
            "enum|is an incomplete entry, which is no type until it is completed",
            "meta.record|is a constructor, whose values are types and which an extern can neither apply nor extend",
            "my_list|is an alias of 'meta.array', which is a constructor, whose values are types and which an extern "
                    + "can neither apply nor extend",
            "a_rec|is a value entry, which is a value and no type",
            "geo|is a schema map, and an extern names one type",
            "nothing|that schema's closure does not hold"})
    void anythingElseIsRefusedNamingTheForeignEntry(String type, String what) {
        assertEquals(List.of("SCHEMA_ERROR /x: 'x' is an extern of '" + type + "' in '" + LIB + "', which " + what
                        + " -- an extern names a type a value can have, declared anywhere in its schema's closure "
                        + "([TSON-SCHEMA] §5.13)"),
                world.validate(extern("x", LIB, type) + "use => { f: x }"),
                () -> joined(world.validate(extern("x", LIB, type) + "use => { f: x }")));
    }
}
