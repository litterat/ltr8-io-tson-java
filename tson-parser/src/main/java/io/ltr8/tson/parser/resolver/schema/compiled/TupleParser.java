package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code tuple} constructor (§5.3), and the {@link
 * TsonSchemaTypeParser} it builds -- {@code List<Object>}, positional like {@link ArrayParser} but with a
 * fixed, heterogeneous arity: each position has its own element type *and* its own {@link
 * ElementState}, unlike array's single shared element type/state. Every position's own parser
 * resolves eagerly at compile time -- all positions are always needed, the same reasoning as
 * {@link RecordParser}'s fields and {@link ArrayParser}'s element type.
 *
 * <p>A tuple is array-shaped on the wire, not record-shaped (matching {@code
 * TsonMapperReader.toTuple}'s own note) -- {@code {}} isn't a plausible reading here at all, only
 * {@link ArrayValue} applies; TSON's own empty array {@code []} is unambiguous already, so unlike
 * {@link RecordParser}/{@link MapParser} there's no {@code EmptyBrace} case to special-case.
 *
 * <p>Arity is fixed and exact -- the data must have precisely as many elements as {@link
 * TupleBody#elements} declares, neither fewer nor more; no {@code min_items}/{@code max_items} the
 * way {@link ArrayParser}/{@link MapParser} have, since a tuple's own arity isn't a range to begin
 * with.
 */
final class TupleParser implements TsonSchemaTypeParser<List<Object>> {

    static final TsonParserFactory FACTORY = (_, name, definition, ctx) -> {
        TupleBody body = (TupleBody) definition.body();
        List<CompiledSlot> slots = new ArrayList<>(body.elements().size());
        for (TupleElement element : body.elements()) {
            slots.add(new CompiledSlot(element, ctx.resolve(element.elementType().name())));
        }
        return new TupleParser(name, slots);
    };

    private record CompiledSlot(TupleElement schema, TsonSchemaTypeParser<?> parser) {
    }

    private final String name;
    private final List<CompiledSlot> slots;

    private TupleParser(String name, List<CompiledSlot> slots) {
        this.name = name;
        this.slots = slots;
    }

    @Override
    public List<Object> read(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected a tuple for '" + name + "', found no value");
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof ArrayValue av)) {
            throw new IllegalArgumentException("expected a tuple (array-shaped) for '" + name + "', found " + core);
        }
        List<ScopedValue> elements = av.elements();
        if (elements.size() != slots.size()) {
            throw new IllegalArgumentException("'" + name + "' has " + slots.size() + " positions, found "
                    + elements.size() + " elements");
        }

        List<Object> result = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            CompiledSlot slot = slots.get(i);
            DataValue elementValue = elements.get(i).value();
            result.add(isAbsent(elementValue) ? defaultOrRequire(slot, i) : slot.parser().read(elementValue));
        }
        return result;
    }

    private static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    private Object defaultOrRequire(CompiledSlot slot, int index) {
        if (slot.schema().state() == ElementState.REQUIRED) {
            throw new IllegalArgumentException(
                    "'" + name + "' position [" + index + "] is absent, but this position is required");
        }
        return null;
    }
}
