package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.ArrayDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An array or a set as a JSON array: [TSON-JSON] §6.3. Elements at the element type, in order, with the size
 * facets validating the slot count.
 *
 * <p><b>Sets share the wire form and differ only in what they refuse.</b> A set-typed position is the same
 * JSON array; duplicates under the element type's equality contract are validation errors at the repeated
 * occurrence ([TSON-SCHEMA] §7.5), and element order on the wire is meaningless. So one reader carries both,
 * the resolved body's own {@code unique_items} deciding whether the duplicate check runs -- refinement never
 * adds or removes a field, so there is no second shape to build a distinct reader for.
 *
 * <p><b>{@code [T?]} admits null at any slot as the absent element</b> -- the slot exists and counts
 * ([TSON-DATA] §2.9). Under {@code [T]} null at a slot is a validation error, as {@code _} is in text: it is
 * never a value (§7).
 */
final class TreeArrayReader implements JsonTypeReader<JsonValue> {

    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        ArrayBody body = (ArrayBody) definition.body();
        return new TreeArrayReader(EntryDisplayName.of(name, definition, context.schema().entries()), body,
                context.readers().resolve(body.elementType().name()), context.locationOf(name, definition));
    };

    private final String name;
    private final ArrayBody body;
    private final JsonTypeReader<?> element;
    private final JsonSchemaLocation schemaLocation;

    /** This array's rules, shared with the TSON reader ([TSON-JSON] §9.4). */
    private final ArrayDiagnostics rules;

    private TreeArrayReader(String name, ArrayBody body, JsonTypeReader<?> element,
                            JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.body = body;
        this.element = element;
        this.schemaLocation = schemaLocation;
        this.rules = new ArrayDiagnostics(name);
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ArrayStart)) {
            ctx.report(rules.notAnArray(JsonAtoms.describe(first)));
            EventSkip.value(ctx, first);
            return JsonNull.INSTANCE;
        }
        List<JsonValue> elements = new ArrayList<>();
        Set<Object> seen = body.uniqueItems() ? new LinkedHashSet<>() : null;
        while (!(ctx.peek() instanceof JsonEvent.ArrayEnd)) {
            int index = elements.size();
            elements.add(readElement(ctx.index(index), index, seen));
        }
        ctx.next();   // ArrayEnd
        checkSize(ctx, elements.size());
        return new JsonArray(elements);
    }

    private JsonValue readElement(JsonReadContext at, int index, Set<Object> seen) {
        if (at.peek() instanceof JsonEvent.NullValue) {
            at.next();
            if (body.state() == ElementState.REQUIRED) {
                at.report(rules.absentElement(index, ABSENT));
            }
            // The slot exists and counts either way, so the placeholder stays in the tree rather than
            // shifting every later element's index against the document ([TSON-DATA] §2.9).
            return JsonNull.INSTANCE;
        }
        JsonValue value = (JsonValue) element.read(at);
        if (seen != null && !seen.add(ValueIdentity.of(value))) {
            at.report(rules.repeatedElement(Nodes.rendered(value)));
        }
        return value;
    }

    /**
     * §6.3: the size facets validate the slot count -- an absent element occupies a slot and is counted.
     *
     * <p>{@code TYPE_MISMATCH} rather than a constraint code, which is the TSON reader's own answer for the
     * same rule. The two must agree: [TSON-JSON] §9.4 gives both encodings one closed vocabulary, and a
     * consumer routing on the code would otherwise see one document refused two different ways.
     */
    /** How a JSON document spells absence (§7), for the `actual` of a rule about an element's state. */
    private static final String ABSENT = "null";

    private void checkSize(JsonReadContext ctx, int count) {
        BigInteger size = BigInteger.valueOf(count);
        body.minItems().filter(min -> size.compareTo(min) < 0)
                .ifPresent(min -> ctx.report(rules.tooFewElements(min, count)));
        body.maxItems().filter(max -> size.compareTo(max) > 0)
                .ifPresent(max -> ctx.report(rules.tooManyElements(max, count)));
    }
}
