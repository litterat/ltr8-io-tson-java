package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.annotation.Typename;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * An enum inside a collection binds to the enum, not to the {@code String} the enum reader produced.
 *
 * <p>A record field needs nothing for this: {@code tson-bind} collects a record's constructor arguments
 * through their bridges, so a scalar enum field arrives as the enum. A collection's elements do not go
 * through a constructor -- they are appended through the collection's own access bridge, which converts
 * nothing -- so the element reader has to apply the bridge itself ({@link ElementBridging}).
 *
 * <p><b>The failure it prevents is silent on the way in.</b> Erasure lets a {@code String} into a {@code
 * List<Colour>} without complaint; the list is heap-polluted and every read of it looks fine until someone
 * iterates with the declared type. The first thing to actually break is the <em>writer</em>, whose bridge is
 * typed for the enum and throws a {@code ClassCastException} naming a class the caller never mentioned --
 * arbitrarily far from the read that caused it. Asserting the element's runtime class is what makes the
 * defect visible where it happens.
 */
class CollectionElementBridgeTest {

    @Typename(name = "palette")
    public record Palette(List<Colour> swatches, Map<String, Colour> named) {
    }

    @Typename(name = "colour")
    public enum Colour {
        RED, GREEN, BLUE
    }

    private static final String DOCUMENT = "{ swatches: [ RED BLUE ]  named: { warm => RED  cool => BLUE } }";

    @Test
    void anEnumInAListBindsToTheEnum() {
        Palette palette = read();

        assertEquals(2, palette.swatches().size());
        assertInstanceOf(Colour.class, palette.swatches().get(0),
                "a list element bound to " + palette.swatches().get(0).getClass() + " rather than the enum");
        assertEquals(List.of(Colour.RED, Colour.BLUE), palette.swatches());
    }

    @Test
    void anEnumInAMapValueBindsToTheEnum() {
        Palette palette = read();

        assertInstanceOf(Colour.class, palette.named().get("warm"),
                "a map value bound to " + palette.named().get("warm").getClass() + " rather than the enum");
        assertEquals(Map.of("warm", Colour.RED, "cool", Colour.BLUE), palette.named());
    }

    /** And the round trip closes: writing back is what a heap-polluted collection breaks first. */
    @Test
    void theBoundValueWritesBackOut() {
        Palette palette = read();

        assertDoesNotThrow(() -> new TsonObjectWriter(SchemaMetaNameBinder.defaultContext()).toTson(palette));
    }

    private static Palette read() {
        return new TsonObjectReader(SchemaMetaNameBinder.defaultContext()).read(DOCUMENT, Palette.class);
    }
}
