package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.unicode.ConfusableNames;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An array or a set as a JSON array, in every read mode: [TSON-JSON] §6.3. Elements at the element reader, in
 * order, with the size facets validating the slot count; the mode's {@link ArrayBuilder} builds the value once.
 *
 * <p><b>{@code [T?]} admits null at any slot as a void element</b> -- the slot exists and counts ([TSON-DATA]
 * §2.9). Under {@code [T]} null at a slot is a validation error, as {@code _} is in text: it is never a value (§7).
 *
 * <p><b>A set's duplicates are judged on the element's value</b>, through {@link ValueIdentity}: what a
 * duplicate is depends on what the element reader produced, so bind mode compares the host values its elements
 * decode to, and tree mode the identity its atom elements carry beside their nodes
 * ({@link ValueIdentity.Identified}).
 *
 * <p><b>Unique names are a look-alike scope</b> ({@link ArrayPlan#elementsAreNames}): an element reading alike with
 * an earlier one is refused at its own index, the name being the identity its element decoded to. A refused or
 * void element is in neither check, there being no value to compare.
 */
final class ArrayReader implements JsonTypeReader<Object> {

    /** How a JSON document spells the void sentinel (§7), for the {@code actual} of a rule about a voidable element. */
    private static final String NULL = "null";

    private final ArrayPlan plan;
    private final JsonTypeReader<?> element;
    private final ArrayBuilder builder;

    ArrayReader(ArrayPlan plan, JsonTypeReader<?> element, ArrayBuilder builder) {
        this.plan = plan;
        this.element = element;
        this.builder = builder;
    }

    ArrayPlan plan() {
        return plan;
    }

    JsonTypeReader<?> element() {
        return element;
    }

    ArrayBuilder builder() {
        return builder;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(plan.schemaLocation());
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ArrayStart)) {
            ctx.report(plan.rules().notAnArray(JsonAtoms.describe(first)));
            EventSkip.value(ctx, first);
            return builder.refused();
        }
        int reportedBefore = ctx.reported();
        List<Object> elements = new ArrayList<>();
        Set<Object> seen = plan.unique() ? new HashSet<>() : null;
        ConfusableNames.Scope names = plan.elementsAreNames() && ctx.identifierPolicy().appliesSkeletonDistinctness()
                ? new ConfusableNames.Scope() : null;
        while (!(ctx.peek() instanceof JsonEvent.ArrayEnd)) {
            int index = elements.size();
            JsonReadContext at = ctx.index(index);
            if (at.peek() instanceof JsonEvent.NullValue) {
                at.next();
                if (!plan.voidableElements()) {
                    at.report(plan.rules().voidElement(index, NULL));
                }
                // The slot exists and counts either way, so it stays rather than shifting every later element's
                // index against the document ([TSON-DATA] §2.9).
                elements.add(Slots.VOID);
                continue;
            }
            Object value = element.read(at);
            if (value == null) {
                elements.add(Slots.REFUSED);
                continue;
            }
            if (seen != null) {
                Object identity = ValueIdentity.of(value);
                if (value instanceof ValueIdentity.Identified identified) {
                    value = identified.value();
                }
                if (!seen.add(identity)) {
                    at.report(plan.rules().repeatedElement(Nodes.rendered(value)));
                } else if (names != null && identity instanceof String name) {
                    Optional<ConfusableNames.Collision> collision = names.add(name);
                    if (collision.isPresent()) {
                        at.report(plan.rules().confusableElements(collision.get()));
                    }
                }
            }
            elements.add(value);
        }
        ctx.next();   // ArrayEnd
        checkSize(ctx, elements.size());
        return builder.build(ctx, elements, ctx.reported() == reportedBefore);
    }

    /**
     * §6.3: the size facets validate the slot count -- a void element occupies a slot and is counted.
     * {@code TYPE_MISMATCH} rather than a constraint code, which is the TSON reader's answer for the same rule:
     * [TSON-JSON] §9.4 gives both encodings one closed vocabulary.
     */
    private void checkSize(JsonReadContext ctx, int count) {
        BigInteger size = BigInteger.valueOf(count);
        plan.minItems().filter(min -> size.compareTo(min) < 0)
                .ifPresent(min -> ctx.report(plan.rules().tooFewElements(min, count)));
        plan.maxItems().filter(max -> size.compareTo(max) > 0)
                .ifPresent(max -> ctx.report(plan.rules().tooManyElements(max, count)));
    }
}
