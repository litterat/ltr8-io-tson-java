package io.ltr8.tson.json.stream;

import io.ltr8.tson.json.JsonPosition;

/**
 * One structural event in a flat, pull-based decomposition of a JSON document -- the streaming
 * counterpart to the nested {@code JsonValue} tree, and what the schema-directed decode of
 * [TSON-JSON] §5-§8 will consume so that a read is bounded by nesting depth rather than by document
 * size.
 *
 * <p>Every value position (the document root, an object member's value, an array element) has the
 * same self-delimiting shape: exactly one core value, either a single leaf event ({@link StringValue},
 * {@link NumberValue}, {@link BooleanValue}, {@link NullValue}) or a matched {@link ObjectStart}/{@link
 * ObjectEnd} or {@link ArrayStart}/{@link ArrayEnd} pair. Inside an object, each member is a {@link
 * MemberName} followed immediately by its value's events.
 *
 * <p><b>There is no document-start event.</b> RFC 8259's {@code JSON-text} is one value and carries no
 * header, so there is nothing for one to hold -- the TSON counterpart exists only because a TSON
 * document opens with its {@code !!id}/{@code !!schema} directives. {@link EndOfDocument} does exist,
 * and is not merely {@code hasNext() == false}: producing it is what pulls past the root value and so
 * what rejects trailing content.
 *
 * <p><b>Six events for six JSON value kinds, one of them {@link NullValue}.</b> This layer is JSON's,
 * so {@code null} is a value here. §7 makes JSON null the absent sentinel's spelling, but that is a
 * fact about reading a document <em>at a typed position</em>, and settling it in the event vocabulary
 * would impose a schema's answer on a layer that has no schema -- which is one of the two
 * disagreements that make this a separate stack from the TSON reader's (see
 * {@code docs/json-encoding.md}).
 */
public sealed interface JsonEvent
        permits JsonEvent.ObjectStart, JsonEvent.MemberName, JsonEvent.ObjectEnd,
        JsonEvent.ArrayStart, JsonEvent.ArrayEnd,
        JsonEvent.StringValue, JsonEvent.NumberValue, JsonEvent.BooleanValue, JsonEvent.NullValue,
        JsonEvent.EndOfDocument {

    /** Where the token that produced this event begins. */
    JsonPosition position();

    /** <code>{</code> -- an object opens; zero or more {@link MemberName}-plus-value pairs follow, then {@link ObjectEnd}. */
    record ObjectStart(JsonPosition position) implements JsonEvent {
    }

    /**
     * One object member's name: announces the member, and its value's events follow immediately.
     *
     * <p>{@code name} is the string's decoded content, so an escaped name and the same name written
     * plainly are one member name here -- which is what makes §3.1's duplicate rule a rule about
     * <em>names</em> rather than about spellings. This layer does not apply it: a repeat is an error
     * whose category follows the position's type (§3.1), which no grammar layer holds.
     */
    record MemberName(String name, JsonPosition position) implements JsonEvent {
    }

    /** <code>}</code> -- the open object closes. */
    record ObjectEnd(JsonPosition position) implements JsonEvent {
    }

    /** {@code [} -- an array opens; zero or more values follow, then {@link ArrayEnd}. */
    record ArrayStart(JsonPosition position) implements JsonEvent {
    }

    /** {@code ]} -- the open array closes. */
    record ArrayEnd(JsonPosition position) implements JsonEvent {
    }

    /** A string, escape-decoded. */
    record StringValue(String value, JsonPosition position) implements JsonEvent {
    }

    /**
     * A number, as its <b>exact source lexeme</b> -- nothing at this layer converts it.
     *
     * <p>§5.3 preserves an exact number's digits and scale and §3.1 forbids rounding one silently, so
     * the digits have to reach the layer that decides the host type. A {@code double} here would have
     * destroyed them before any rule could keep them.
     */
    record NumberValue(String literal, JsonPosition position) implements JsonEvent {
    }

    /** {@code true} or {@code false}. */
    record BooleanValue(boolean value, JsonPosition position) implements JsonEvent {
    }

    /** {@code null} -- a JSON value here; the absent sentinel only once a typed position reads it (§7). */
    record NullValue(JsonPosition position) implements JsonEvent {
    }

    /**
     * The document's one value is complete and only end of input remains.
     *
     * <p><b>Pulling this is what rejects trailing content</b>, so a consumer that stops at the root
     * value's last event has not finished reading the document -- {@code "[1] junk"} is refused here
     * and nowhere else. The TSON facades keep the same trap under {@code requireDocumentEnd}.
     */
    record EndOfDocument(JsonPosition position) implements JsonEvent {
    }
}
