package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <b>A binding map is written in the names the author wrote.</b> A family member composing an application of an
 * parametric record ({@code ping => msg_of<ping_body> & { … }}) is a declaration like any other, so a bind context keys it
 * by its own name, whether the application's argument is a record or a container ([TSON-SCHEMA] §5.8, §5.10).
 */
class AppliedOperandBindingTest {

    private static final String ENVELOPE_ID = "https://example.test/envelope.tn";

    /** A discriminated family whose members compose a parametric record's applications (§5.10). */
    private static final String ENVELOPE = """
            !!id:"https://example.test/envelope.tn"
            !!import:"%s"
            !!import:"%s"
            !schema {
              msg    => abstract { kind: text =? }
              msg_of => <V> msg & { body: V }

              ping_body => { seq: int32 }
              pong_body => { seq: int32  latency: int32 }

              ping => msg_of<ping_body> & { kind?: = "ping" }
              pong_bodies => [pong_body]
              pong => msg_of<pong_bodies> & { kind?: = "pong" }

              channel => { m: msg }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    public sealed interface Msg permits Ping, Pong {
    }

    public record PingBody(int seq) {
    }

    public record PongBody(int seq, int latency) {
    }

    public record Ping(PingBody body) implements Msg {
    }

    public record Pong(List<PongBody> body) implements Msg {
    }

    public record Channel(Msg m) {
    }

    private static Tson bound(String schema, Map<String, Class<?>> names) {
        SchemaSource source = uri -> schema;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(names).orElse(SchemaModelNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
        tson.resolve(schema);
        return tson;
    }

    /** Every name here is one the author wrote. */
    private static Tson envelope() {
        return bound(ENVELOPE, Map.of(
                "channel", Channel.class, "msg", Msg.class,
                "ping", Ping.class, "pong", Pong.class,
                "ping_body", PingBody.class, "pong_body", PongBody.class));
    }

    @Test
    void aMemberComposingAnApplicationBindsUnderItsName() {
        Channel read = envelope().objectReader().read("""
                !!import:"%s"
                !channel { m: { kind: ping  body: { seq: 1 } } }""".formatted(ENVELOPE_ID), Channel.class);

        assertEquals(new Ping(new PingBody(1)), read.m());
    }

    /** The same where the application's argument is a declared container. */
    @Test
    void aMemberOverAContainerPayloadBindsTheSameWay() {
        Channel read = envelope().objectReader().read("""
                !!import:"%s"
                !channel { m: { kind: pong  body: [ { seq: 1  latency: 7 } ] } }""".formatted(ENVELOPE_ID),
                Channel.class);

        assertEquals(new Pong(List.of(new PongBody(1, 7))), read.m());
    }

    // ── A class that cannot be described keeps the reason it could not ───

    private static final String BOXES_ID = "https://example.test/boxes.tn";

    private static final String BOXES = """
            !!id:"https://example.test/boxes.tn"
            !!import:"%s"
            !!import:"%s"
            !schema {
              box     => <V> { item: V }
              int_box => box<int32>
              boxes   => { a: int_box }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /** The generic class a code generator would emit for a parametric record: its component erases to {@code Object}. */
    public record Box<V>(V item) {
    }

    public record Boxes(Box<Integer> a) {
    }

    /**
     * <b>The reason a class could not be bound is the cause, not a lost sentence.</b> An erased component is
     * refused by {@code ltr8-bind} ("no valid data conversion for class java.lang.Object"), and a report that
     * appends the text but drops the cause leaves the caller with a name and no account of what about the
     * class was wrong. The parametric record {@code box} bound to the same class is never asked: nothing reads it
     * bare ([TSON-SCHEMA] §5.10), so only its application {@code int_box} fails the compile.
     */
    @Test
    void aClassThatCannotBeDescribedFailsTheCompileWithItsCause() {
        Tson tson = bound(BOXES, Map.of("boxes", Boxes.class, "int_box", Box.class, "box", Box.class));

        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson.bindRegistry().get(BOXES_ID));

        assertEquals("'int_box' of example.test/boxes.tn binds " + Box.class.getName() + ", which cannot be analysed: "
                + "Unable to find a valid data conversion for class: class java.lang.Object. Bind 'int_box' to a class "
                + "this bind context can describe, or leave it out of the binding map", thrown.getMessage());
        assertNotNull(thrown.getCause(), "the cause is what says which component could not be bound");
    }
}
