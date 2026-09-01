package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [TSON-SCHEMA] §8.2's derived names, pinned by value.
 *
 * <p><b>The hash is not normative and these expectations are not a requirement.</b> §8.2 keys a closed
 * synthetic on structural equality and leaves the spelling to the implementation, which is why {@code
 * ResolvedForm} reduces every hash to a placeholder before comparing against the spec's own fixtures. That
 * is the right thing for a conformance comparison and it means those fixtures, and the {@code class2/schema/}
 * corpus layer with them, cannot see a rendering change at all.
 *
 * <p>So what these assertions are for is the other question: that a change to the rendering is <b>deliberate
 * rather than accidental</b>. An entry name is part of the resolved form and reaches an importing schema,
 * which derives the same name for the same form -- so the spelling being non-normative does not make it free
 * to drift between two builds of this library.
 *
 * <p><b>Both channels, because they are two functions.</b> {@link DerivedName#ofBinding} names a binding
 * record and {@link DerivedName#ofApplication} names an application; they render under the same tag letters
 * in different roles and share only their two leaves. {@code SchemaDesugarerTest} already pins the binding
 * side end to end; the application side had no value-level guard at all -- every assertion on one tested the
 * readable half with {@code startsWith}, which a change to the hashed rendering passes.
 */
class DerivedNameTest {

    private static RecordValue.Field slot(String name, String value) {
        return WireForm.nameField(name, value);
    }

    private static TypeArgument type(String name) {
        return new TypeArgument.Ref(TypeRef.of(name));
    }

    private static TypeArgument value(String text) {
        return new TypeArgument.Value(new Token(text, Token.Form.UNQUOTED));
    }

    @Test
    void aBindingRecordIsNamedByItsHeadAndSlots() {
        assertEquals("array_text_4cc4a482",
                DerivedName.ofBinding("array", List.of(slot("element_type", "text"))));
        assertEquals("map_text_integer_5c4af9ec",
                DerivedName.ofBinding("map", List.of(slot("key_type", "text"), slot("value_type", "integer"))));
    }

    @Test
    void anApplicationIsNamedByItsHeadAndArguments() {
        assertEquals("box_text_04117bb4", DerivedName.ofApplication("box", List.of(type("text"))));
        assertEquals("pair_uuid_text_4b15adf4",
                DerivedName.ofApplication("pair", List.of(type("uuid"), type("text"))));
        assertEquals("grid_int32_125f0dd6", DerivedName.ofApplication("grid", List.of(type("int32"))));
    }

    /** A value argument goes through {@code NumericIdentity}, which is the leaf the two channels share. */
    @Test
    void aValueArgumentIsNamedByItsCanonicalMagnitude() {
        assertEquals("vec_float32_3_cc179805",
                DerivedName.ofApplication("vec", List.of(type("float32"), value("3"))));
        assertEquals(DerivedName.ofApplication("vec", List.of(type("float32"), value("255"))),
                DerivedName.ofApplication("vec", List.of(type("float32"), value("0xFF"))),
                "§4.3's equivalence applies where identity is derived: 255 and 0xFF are one application");
    }

    /**
     * The two channels are separate functions and must not be collapsed into one: a binding record renders
     * its slots under their own field names where an application renders its arguments positionally, so the
     * same head and the same text land on different entries.
     */
    @Test
    void theTwoChannelsAreNotOneFunction() {
        assertNotEquals(DerivedName.ofBinding("box", List.of(slot("v", "text"))),
                DerivedName.ofApplication("box", List.of(type("text"))));
    }
}
