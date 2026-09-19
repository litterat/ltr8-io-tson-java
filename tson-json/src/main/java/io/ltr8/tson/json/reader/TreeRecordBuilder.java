package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;

import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonValue;

/**
 * Tree mode's record: a {@link JsonObject} of the members the document stated or the schema injected, in
 * <b>declaration order</b> rather than arrival order.
 *
 * <p>§6.1.6 gives member order no meaning, so a decoder is free to choose one -- and choosing the schema's makes
 * that freedom observable: two documents differing only in member order decode to one tree, which is what "order
 * is presentation" has to mean if anything downstream compares decoded output. It is also the order §6.1.6 asks
 * an encoder for, so a tree written straight back out is already in it, and an injected default lands where its
 * field is declared instead of appended after everything the document happened to state.
 *
 * <p><b>Tree mode keeps what it built.</b> A refused member, and an {@code OPTIONAL_FIXED = _} member written
 * null, both stand as {@link JsonNull}: the first is the placeholder a diagnostic beside it explains, the second
 * the value itself, presence being the information ([TSON-SCHEMA] §5.2). An absent member is left out.
 */
final class TreeRecordBuilder implements RecordBuilder {

    /**
     * Tree mode's concrete record reader: every field read at its schema reader, and every schema-stated value
     * injected as the JSON node that spells it. {@link DispatchFactories} decides whether a record position gets
     * this or a dispatcher in front of it.
     */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        RecordPlan plan = new RecordPlan(name, definition, context);
        Object[] injected = new Object[plan.stated.length];
        for (int i = 0; i < injected.length; i++) {
            injected[i] = plan.stated[i] == null ? null : plan.stated[i].node();
        }
        return new RecordReader(plan, plan.schemaReaders, injected, new TreeRecordBuilder(plan.names));
    };

    private final String[] names;

    private TreeRecordBuilder(String[] names) {
        this.names = names;
    }

    @Override
    public Object build(JsonReadContext ctx, Object[] slots, boolean clean) {
        JsonObject.Builder members = JsonObject.builder(slots.length);
        for (int i = 0; i < slots.length; i++) {
            Object slot = slots[i];
            if (slot == null || slot == Slots.ABSENT) {
                continue;
            }
            members.put(names[i], slot == Slots.REFUSED || slot == Slots.NULL_KEPT
                    ? JsonNull.INSTANCE
                    : (JsonValue) slot);
        }
        return members.build();
    }

    @Override
    public Object refused() {
        return JsonNull.INSTANCE;
    }
}
