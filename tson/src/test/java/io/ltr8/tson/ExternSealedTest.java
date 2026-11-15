package io.ltr8.tson;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.extern;
import static io.ltr8.tson.ExternWorld.joined;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where an extern stands, and what it is sealed against ([TSON-SCHEMA] §5.13): a name at every type position, a type
 * argument, a bound and an alias target; never an operand of {@code &}, {@code -} or {@code ^}, a construction or
 * application head, or the type of a field carrying a default or a pin, through an alias of it as directly; and no
 * qualified name walks through one (§5.12).
 */
class ExternSealedTest {

    private static final String LIB = "https://example.test/lib.tn";
    private static final String SEALED = " -- an extern is sealed: it names another schema's type as that schema "
            + "declares it, so it takes no '&', '-' or '^', heads no construction or application, and a field typed "
            + "by it takes no default or pin ([TSON-SCHEMA] §5.13)";
    private static final String O1 = "an extern of 'order' in '" + ORDER_1 + "'";
    private static final String CNT = "an extern of 'count' in '" + LIB + "'";

    private final ExternWorld world = new ExternWorld().schema(LIB, "count => !integer ^ { min: 0 }");

    private List<String> withExterns(String body) {
        return world.validate(extern("o1", ORDER_1, "order") + extern("cnt", LIB, "count") + body);
    }

    @Test
    void anExternStandsAtEveryTypePositionAndAsAnArgumentABoundAndAnAliasTarget() {
        assertEquals(List.of(), withExterns("""
                o1_alias => o1
                page     => <T> { items: [T] }
                bounded  => <T: o1> { item: T }
                orders   => page<o1>
                one      => bounded<o1>
                holder   => {
                  field:  o1
                  list:   [o1]
                  keyed:  { cnt => o1 }
                  pair:   [o1, cnt]
                  choice: (o1 | text)
                  hop:    o1_alias
                  kind?:  meta.ref<meta.record> ~ o1
                }
                """));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "y => o1 & { extra: text }|'y': '&' and '-' compose 'o1', which is O1",
            "y => o1 - { id }|'y': '&' and '-' compose 'o1', which is O1",
            "y => !cnt ^ { max: 9 }|'y': '^' refines 'cnt', which is CNT",
            "y => !o1 { id: x }|'y': '!o1 { ... }' constructs 'o1', which is O1",
            "y => o1<text>|'y': 'o1<...>' applies 'o1', which is O1",
            "y => { f: o1<text> }|'y': 'o1<...>' applies 'o1', which is O1",
            "y => { f?: cnt ~ 3 }|'y': field 'f': its default 3 is a value of 'cnt', which is CNT",
            "a => o1\\ny => a & { more: text }|'y': '&' and '-' compose 'a', an alias of 'o1', which is O1",
            "a => cnt\\ny => { f: a = 3 }|'y': field 'f': its fixed value 3 is a value of 'a', an alias of 'cnt', "
                    + "which is CNT"})
    void anOperationAnExternIsSealedAgainstIsRefusedInOneWording(String body, String refusal) {
        String entry = "/y";
        List<String> reported = withExterns(body.replace("\\n", "\n"));
        assertEquals(List.of("SCHEMA_ERROR " + entry + ": " + refusal.replace("O1", O1).replace("CNT", CNT) + SEALED),
                reported, () -> joined(reported));
    }

    @Test
    void noQualifiedNameWalksThroughAnExtern() {
        assertEquals(List.of("SCHEMA_ERROR /v: 'v' at /r names 'o1.payment', and 'o1' is " + O1 + ": no name walks "
                        + "through an extern into another schema's namespace, so 'payment' selects nothing "
                        + "([TSON-SCHEMA] §5.12, §5.13)"),
                withExterns("api => { r: meta.ref<meta.record> }\nv => !api { r: o1.payment }"));
    }

    @Test
    void anExternIsATypeAndNoValueAtASlotNamingValues() {
        assertEquals(List.of("SCHEMA_ERROR /v: 'v' at /r wants an 'order', and 'o1' is " + O1 + ", a type and no "
                        + "value -- a slot over 'order' holds the name of a value ([TSON-SCHEMA] §5.12)"),
                world.validate(extern("o1", ORDER_1, "order") + "api => { r: meta.ref<order> }\nv => !api { r: o1 }",
                        ORDER_1));
    }
}
