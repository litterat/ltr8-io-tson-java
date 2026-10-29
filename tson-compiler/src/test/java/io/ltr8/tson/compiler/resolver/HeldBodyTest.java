package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The properties the held-body split is chosen for. [TSON-SCHEMA] §5.10 makes an open entry's body the
 * constructor application <em>as written</em>, so {@link TemplateBody} carries it as text and this package's
 * {@link HeldBody} parses that text for the phases that need a tree. Neither half is visible from its own
 * declaration: one is a module-boundary arrangement, one is a decision about {@link Top}'s
 * equality that goes the opposite way from the component beside it, and one is a round trip that has to hold
 * or two entries that should be equal are not.
 */
class HeldBodyTest {

    /** {@code box => <T> !<head> {}} -- the smallest held application that differs by its constructor. */
    private static Top template(String head) {
        return HeldBody.held(List.of("T"), new DataValue(List.of(), Optional.of(head),
                        new RecordValue(List.of())));
    }

    /**
     * The point of carrying the body as text: {@code tson-schema} states an open entry's body without naming
     * a {@code tson-compiler} type, so {@link Top} stays sealed, {@code Top.body} stays REQUIRED,
     * and a template needs no second body component.
     */
    @Test
    void aHeldApplicationIsABodyTheValueModelCanCarry() {
        Top body = template("tuple");

        TemplateBody held = assertInstanceOf(TemplateBody.class, body);
        assertEquals(List.of("T"), held.parameterNames());
        assertEquals("!tuple {}", held.template());
    }

    /**
     * Held bodies are content, so they participate in equality -- unlike {@code position}, which is excluded
     * so that two parses of one declaration compare equal. Two templates with different bodies are different
     * templates, and nothing else on the entry distinguishes them.
     */
    @Test
    void twoTemplatesDifferingOnlyInTheirHeldBodyAreNotEqual() {
        assertEquals(template("tuple"), template("tuple"));
        assertNotEquals(template("tuple"), template("array"));
    }

    /**
     * A held body writes as the application it holds and <b>nothing else</b>: the AST is written as syntax
     * rather than bound as a value. Bound, it would render a faithful description of the wrong thing --
     * {@code !recordvalue { fields: [ ... ] }} -- and the token forms an author chose would be decided again
     * rather than put back.
     */
    @Test
    void aHeldBodyIsTheApplicationItHolds() {
        TemplateBody held = HeldBody.held(List.of("T"), new DataValue(List.of(), Optional.of("choice"),
                new RecordValue(List.of(new RecordValue.Field("variants", plain(new ArrayValue(List.of(
                        plain(new TokenValue("T", TokenForm.UNQUOTED)),
                        plain(new TokenValue("error", TokenForm.UNQUOTED))))))))));

        assertEquals("!choice { variants: [ T error ] }", held.template());
    }

    /**
     * <b>And it parses back to the tree it was written from.</b> This is the round trip the whole split rests
     * on: {@link HeldBody#held} emits and {@link HeldBody#of} parses, and {@code held} hands back no tree of
     * its own, so a disagreement between the writer and the parser about §5.10's one spelling fails here
     * rather than showing up later as two entries that ought to be equal and are not.
     */
    @Test
    void whatAHeldBodyWritesParsesAgain() {
        DataValue application = new DataValue(List.of(), Optional.of("array"),
                new RecordValue(List.of(new RecordValue.Field("element_type",
                        plain(new TokenValue("T", TokenForm.UNQUOTED))))));

        TemplateBody held = HeldBody.held(List.of("T"), application);

        assertEquals(application, HeldBody.of(held).application());
    }

    private static DataValue plain(CoreValue value) {
        return new DataValue(List.of(), Optional.empty(), value);
    }

    /** A record template is a record with parameters, never held text (§5.10): the door refuses one. */
    @Test
    void aRecordIsNeverHeld() {
        assertThrows(IllegalStateException.class, () -> template("record"));
    }

    /** A held body belongs to an entry that declares parameters; one with none is a closed entry (§5.10). */
    @Test
    void aHeldBodyBelongsToAnEntryThatDeclaresParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateBody(List.of(), "!set_type { element_type: T }"));
        assertThrows(NullPointerException.class,
                () -> new TemplateBody(List.of(TemplateParam.typeParameter("T")), null));
    }
}
