package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A binding map is written in the names the author wrote.</b> An entry a template application materialised
 * is named by content ([TSON-SCHEMA] §8.2 -- resolver-chosen, fresh, unreachable from source), so a bind
 * context keyed on one cannot be written by hand and cannot be emitted by a generator: the hash is not known
 * until the schema resolves. The lookup therefore asks under the name the referring entry carries and falls
 * back to the minted one.
 *
 * <p>This is the binding twin of the rule {@code EntryDisplayName} applies to messages -- a type is named by
 * what the author wrote, never by a content-derived entry name.
 */
class AliasedInstantiationBindingTest {

    private static final String ENVELOPE_ID = "https://example.test/envelope.tn";

    /** A discriminated family whose members are minted by a subtype-template factory (§5.10, #10). */
    private static final String ENVELOPE = """
            !!id:"https://example.test/envelope.tn"
            !!meta:"%s"
            !!import:"%s"
            {
              msg    => abstract { kind: text =? }
              msg_of => <T, V> msg & { kind?: = T  body: V }

              ping_body => { seq: int32 }
              pong_body => { seq: int32  latency: int32 }

              ping => msg_of<"ping", ping_body>
              pong => msg_of<"pong", [pong_body]>

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
                .nameBinder(DataNameBinder.ofMap(names).orElse(SchemaMetaNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
        tson.resolve(schema);
        return tson;
    }

    /** Every name here is one the author wrote: no entry is mapped by its minted name, because none can be. */
    private static Tson envelope() {
        return bound(ENVELOPE, Map.of(
                "channel", Channel.class, "msg", Msg.class,
                "ping", Ping.class, "pong", Pong.class,
                "ping_body", PingBody.class, "pong_body", PongBody.class));
    }

    @Test
    void anAliasedInstantiationBindsUnderTheNameTheAuthorWrote() {
        Channel read = envelope().objectReader().read("""
                !!schema:"%s"
                !channel { m: { kind: ping  body: { seq: 1 } } }""".formatted(ENVELOPE_ID), Channel.class);

        assertEquals(new Ping(new PingBody(1)), read.m());
    }

    /** The same through a container payload, whose member entry is minted over a synthetic of its own. */
    @Test
    void anAliasedInstantiationOverAContainerPayloadBindsTheSameWay() {
        Channel read = envelope().objectReader().read("""
                !!schema:"%s"
                !channel { m: { kind: pong  body: [ { seq: 1  latency: 7 } ] } }""".formatted(ENVELOPE_ID),
                Channel.class);

        assertEquals(new Pong(List.of(new PongBody(1, 7))), read.m());
    }

    // ── A missing binding keeps the reason it was missing ────────────────

    private static final String BOXES_ID = "https://example.test/boxes.tn";

    private static final String BOXES = """
            !!id:"https://example.test/boxes.tn"
            !!meta:"%s"
            !!import:"%s"
            {
              box     => <V> { item: V }
              int_box => box<int32>
              boxes   => { a: int_box }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /** The generic class a code generator would emit for a template: its component erases to {@code Object}. */
    public record Box<V>(V item) {
    }

    public record Boxes(Box<Integer> a) {
    }

    /**
     * <b>The reason a class could not be bound is the cause, not a lost sentence.</b> An erased component is
     * refused by {@code tson-bind} ("no valid data conversion for class java.lang.Object"), and a report that
     * appends the text but drops the cause leaves the caller with a name and no account of what about the
     * class was wrong.
     */
    @Test
    void aMissingBindingCarriesTheCauseThatMadeItMissing() {
        Tson tson = bound(BOXES, Map.of("boxes", Boxes.class, "int_box", Box.class, "box", Box.class));

        MissingBindingException thrown = assertThrows(MissingBindingException.class,
                () -> tson.objectReader().read("""
                        !!schema:"%s"
                        !boxes { a: { item: 1 } }""".formatted(BOXES_ID), Boxes.class));

        assertNotNull(thrown.getCause(), "the cause is what says which component could not be bound");
        StringBuilder chain = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        assertTrue(chain.toString().contains("java.lang.Object"), chain.toString());
    }
}
