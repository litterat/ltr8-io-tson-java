package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bind mode's map, in either form: an unmodifiable {@code Map} of the keys' and values' host values, or -- where a
 * component declares one -- that component's own map class, its keys and values bound to the class's key and value.
 * All-or-nothing, as every bind builder is: a map whose read reported anything -- a repeated key included -- builds
 * nothing.
 */
final class BindMapBuilder implements MapBuilder {

    /** Bind mode's map reader for a position nothing more specific declares: the entries into a {@code Map}. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        MapPlan plan = MapPlan.of(name, definition, context);
        BindMapBuilder builder = new BindMapBuilder(null);
        return plan.objectForm()
                ? new MapObjectReader(plan, plan.keyParser(), null, plan.schemaValue(), builder)
                : new MapPairsReader(plan, plan.schemaKey(), plan.schemaValue(), builder);
    };

    /**
     * {@code map} read again for {@code target}: the object form's key parser bound to the target's key class
     * (through its bridge), the pairs form's key reader and either form's value reader bound through {@link
     * BindTargets}.
     */
    static JsonTypeReader<?> forTarget(JsonTypeReader<?> map, DataClassMap target, String what,
                                       List<String> mismatches) {
        String targetName = target.typeClass().getSimpleName();
        BindMapBuilder builder = new BindMapBuilder(target);
        if (map instanceof MapObjectReader object) {
            MapPlan plan = object.plan();
            JsonTypeReader<?> value = BindTargets.to(object.value(), target.valueDataClass(), what + "'s value",
                    targetName + "'s value", mismatches);
            if (!(target.keyDataClass() instanceof DataClassAtom key)) {
                mismatches.add(what + "'s key is an atom, and " + targetName + "'s key binds "
                        + target.keyDataClass().typeClass().getName() + " structurally");
                return map;
            }
            Optional<AtomType<?>> keyParser = plan.keyParser().boundTo(key.dataClass());
            if (keyParser.isEmpty()) {
                mismatches.add(what + "'s key cannot produce " + key.dataClass().getName() + ", which is what "
                        + targetName + "'s key binds");
                return map;
            }
            return new MapObjectReader(plan, keyParser.get(), key.bridge().orElse(null), value, builder);
        }
        MapPairsReader pairs = (MapPairsReader) map;
        return new MapPairsReader(pairs.plan(),
                BindTargets.to(pairs.key(), target.keyDataClass(), what + "'s key", targetName + "'s key",
                        mismatches),
                BindTargets.to(pairs.value(), target.valueDataClass(), what + "'s value", targetName + "'s value",
                        mismatches),
                builder);
    }

    /** The target's descriptor, or null for the natural {@code Map}. */
    private final DataClassMap target;

    private BindMapBuilder(DataClassMap target) {
        this.target = target;
    }

    @Override
    public Object build(JsonReadContext ctx, List<String> names, List<Object> keys, List<Object> values,
                        boolean clean) {
        if (!clean) {
            return null;
        }
        if (target == null) {
            Map<Object, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                map.put(keys.get(i), value(values.get(i)));
            }
            return Collections.unmodifiableMap(map);
        }
        try {
            Object built = target.constructor().invoke(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                target.put().invoke(built, keys.get(i), value(values.get(i)));
            }
            return built;
        } catch (Throwable e) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "%s rejected the entries read for it: %s"
                    .formatted(target.typeClass().getSimpleName(), e), "entries "
                    + target.typeClass().getSimpleName() + " accepts", String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Override
    public Object refused() {
        return null;
    }

    private static Object value(Object slot) {
        return slot == Slots.ABSENT ? null : slot;
    }
}
