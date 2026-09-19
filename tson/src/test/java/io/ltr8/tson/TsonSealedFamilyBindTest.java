package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>A sealed family binds to Java classes with its selector flat beside each member's own fields</b> --
 * [TSON-SCHEMA] §5.2's internally tagged layout, and the bind-mode peer of {@link TsonSealedFamilyReadTest}.
 * {@code AliasedInstantiationBindingTest} covers the adjacently tagged layout, whose payload is nested under a
 * single field.
 *
 * <p><b>What the layout costs the binding is the point.</b> The dispatch is one reader for both modes and
 * hands the whole value to the selected member, so nothing in it is layout-aware; what differs is the class
 * the member binds to. A member's class needs <b>no component for the pinned selector</b> -- §5.2 makes a
 * FIXED field exempt from the schema-and-class agreement, the schema settling its value -- which is what lets
 * {@code Ping(int id, int seq)} agree with a schema declaring {@code kind} beside {@code id} and {@code seq}.
 */
class TsonSealedFamilyBindTest {

    private static final String ID = "https://example.test/inline-envelope.tn";

    /** The flat layout: the selector sits beside each member's own fields, with no payload wrapper. */
    private static final String SCHEMA = """
            !!id:"https://example.test/inline-envelope.tn"
            !!meta:"%s"
            !!import:"%s"
            {
              msg  => @abstract { kind: text =?  id: int32 }
              ping => msg & { kind: = "ping"  seq: int32 }
              pong => msg & { kind: = "pong"  latency: int32 }

              channel => { m: msg }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    public sealed interface Msg permits Ping, Pong {
    }

    /** No component for {@code kind}: it is REQUIRED_FIXED, which §5.2 exempts from the binding agreement. */
    public record Ping(int id, int seq) implements Msg {
    }

    public record Pong(int id, int latency) implements Msg {
    }

    public record Channel(Msg m) {
    }

    private static Tson bound() {
        SchemaSource source = uri -> SCHEMA;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of(
                        "channel", Channel.class, "msg", Msg.class,
                        "ping", Ping.class, "pong", Pong.class)).orElse(SchemaMetaNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
        tson.resolve(SCHEMA);
        return tson;
    }

    @Test
    void anInlineDiscriminatorDispatchesAndBindsTheSelectedMember() {
        Channel read = bound().objectReader().read("""
                !!schema:"%s"
                !channel { m: { kind: ping  id: 1  seq: 2 } }""".formatted(ID), Channel.class);

        assertEquals(new Ping(1, 2), read.m());
    }

    /** The other member, so the selection is a selection and not a default. */
    @Test
    void theOtherPinBindsTheOtherMember() {
        Channel read = bound().objectReader().read("""
                !!schema:"%s"
                !channel { m: { kind: pong  id: 3  latency: 7 } }""".formatted(ID), Channel.class);

        assertEquals(new Pong(3, 7), read.m());
    }

    /**
     * A record's fields have no significant order, so the selector may arrive after the fields it selects --
     * the lookahead the dispatcher makes, exercised in bind mode, where the construction guard makes a value
     * all-or-nothing and a rewind that lost a field would show as a missing component rather than a bad read.
     */
    @Test
    void theSelectorMayArriveLastInBindMode() {
        Channel read = bound().objectReader().read("""
                !!schema:"%s"
                !channel { m: { seq: 2  id: 1  kind: ping } }""".formatted(ID), Channel.class);

        assertEquals(new Ping(1, 2), read.m());
    }
}
