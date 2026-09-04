package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The two properties the held-body carrier is chosen for. Neither is visible from its declaration: one is a
 * module-boundary arrangement and the other is a decision about {@link TypeDefinition}'s equality that goes
 * the opposite way from the component beside it.
 */
class HeldBodyTest {

    /** {@code box => <T> !<head> {}} -- the smallest held application that differs by its constructor. */
    private static TypeDefinition template(String head) {
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("T"),  List.of(),
                List.of(), Optional.empty(),
                new HeldBody(new DataValue(List.of(), Optional.of(head), new RecordValue(List.of()))));
    }

    /**
     * The point of declaring {@link TemplateBody} in {@code schema.meta} at all: {@code tson-compiler}'s own
     * AST can stand as a resolved body without {@code tson-schema} naming a {@code tson-compiler} type, so a
     * template needs no second body component and {@code TypeDefinition.body} stays REQUIRED.
     */
    @Test
    void aHeldApplicationIsABodyTheValueModelCanCarry() {
        Top body = template("record").body();

        assertInstanceOf(TemplateBody.class, body);
        assertInstanceOf(HeldBody.class, body);
    }

    /**
     * Held bodies are content, so they participate in equality -- unlike {@code position}, which is excluded
     * so that two parses of one declaration compare equal. Two templates with different bodies are different
     * templates, and nothing else on the entry distinguishes them.
     */
    @Test
    void twoTemplatesDifferingOnlyInTheirHeldBodyAreNotEqual() {
        assertEquals(template("record"), template("record"));
        assertNotEquals(template("record"), template("array"));
    }

    /**
     * A held body writes as the application it holds and <b>nothing else</b>: the carrier is {@code
     * @Transparent}, so it contributes neither a type-ref nor a field name, and the AST inside is written as
     * syntax rather than bound as a value ({@code AstWriter}). Bound, it would render a faithful description
     * of the wrong thing -- {@code !recordvalue { fields: [ ... ] }} -- and the token forms an author chose
     * would be decided again rather than put back.
     */
    @Test
    void aHeldBodyWritesAsTheApplicationItHolds() {
        HeldBody held = new HeldBody(new DataValue(List.of(), Optional.of("choice"),
                new RecordValue(List.of(new RecordValue.Field("variants", scoped(new ArrayValue(List.of(
                        scoped(new TokenValue("T", TokenForm.UNQUOTED)),
                        scoped(new TokenValue("error", TokenForm.UNQUOTED))))))))));

        assertEquals("!choice { variants: [ T error ] }", new TsonObjectWriter().toTson(held));
    }

    /**
     * What it writes reads back <em>whole</em>, which is the point of writing the source rather than a
     * description of it -- and, with the carrier transparent, needs no unwrapping step on the way: the
     * document a held body writes is the application, so parsing it yields the very value that was held.
     */
    @Test
    void whatAHeldBodyWritesParsesAgain() {
        DataValue application = new DataValue(List.of(), Optional.of("array"),
                new RecordValue(List.of(new RecordValue.Field("element_type",
                        scoped(new TokenValue("T", TokenForm.UNQUOTED))))));

        String written = new TsonObjectWriter().toTson(new HeldBody(application));

        assertEquals(application, new TsonDataParser(written).parseDocument().root());
    }

    private static ScopedValue scoped(CoreValue value) {
        return new ScopedValue(Optional.empty(), new DataValue(List.of(), Optional.empty(), value));
    }

    @Test
    void thereIsAlwaysAnApplicationToHold() {
        assertThrows(IllegalArgumentException.class, () -> new HeldBody(null));
    }
}
