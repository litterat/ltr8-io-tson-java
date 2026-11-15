package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.IncompleteEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * Construct {@code C} from a base with data where the base or the result is an {@link IncompleteEntry}
 * ([TSON-SCHEMA] §5.5): {@code !C ^ { … }}, {@code !X ^ { … }} and the completion of {@code X}. All of it is on the
 * binding record as written, an incomplete entry having no bound body.
 *
 * <p><b>A field is written once.</b> A refinement or a completion of an incomplete entry writes only fields the
 * entry has not written, and one written again is refused whatever its value: narrowing is a relation between types,
 * judged by a facet's kind once the entry is complete, and an incomplete entry only accumulates.
 */
public final class IncompleteEntries {

    private IncompleteEntries() {
    }

    /** Why {@code name}, naming {@code incomplete}, is refused where a type is named. */
    public static String noType(String name, IncompleteEntry incomplete) {
        return "an incomplete '" + incomplete.constructor() + "' with " + incomplete.absent() + " absent, which is "
                + "no type -- complete it where it is used ('!" + name + " { ... }'), or declare its completion and "
                + "name that";
    }

    /** {@code constructor}'s fields a construction writes, in declaration order: neither optional nor pinned. */
    static List<String> required(RecordBody constructor) {
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, RecordField> field : constructor.fields().entrySet()) {
            if (!field.getValue().optional() && field.getValue().role() == FieldRole.FREE) {
                required.add(field.getKey());
            }
        }
        return required;
    }

    /** The fields {@code incomplete} writes, in the order written. */
    static SequencedMap<String, DataValue> written(IncompleteEntry incomplete) {
        return braced((DataValue) incomplete.written()).orElseThrow(() -> new IllegalStateException(
                "an incomplete entry holds " + incomplete.written() + ", which is no binding record"));
    }

    /** A binding record's fields by name, empty where {@code body} is no braced record. */
    static Optional<SequencedMap<String, DataValue>> braced(DataValue body) {
        SequencedMap<String, DataValue> fields = new LinkedHashMap<>();
        switch (body.coreValue()) {
            case RecordValue record -> record.fields().forEach(field -> fields.put(field.name(), field.value()));
            case EmptyBrace ignored -> {
            }
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(fields);
    }

    /** The binding record tagged {@code tag} writing {@code fields}. */
    static DataValue record(DataValue annotated, String tag, SequencedMap<String, DataValue> fields) {
        List<RecordValue.Field> written = new ArrayList<>(fields.size());
        fields.forEach((name, value) -> written.add(new RecordValue.Field(name, value)));
        return new DataValue(annotated.annotations(), Optional.of(tag),
                written.isEmpty() ? new EmptyBrace() : new RecordValue(written));
    }

    /**
     * Refuses a field of {@code written} that {@code constructor} does not declare, or fixes: a pinned field is the
     * constructor's, supplied by its reader.
     */
    static void requireWritable(String name, String constructorName, RecordBody constructor,
                                SequencedMap<String, DataValue> written) {
        for (String field : written.keySet()) {
            RecordField declared = constructor.fields().get(field);
            if (declared == null) {
                throw new SchemaValidationException("'" + name + "': '" + field + "' is no field of the constructor '"
                        + constructorName + "' (§5.5)");
            }
            if (declared.role() == FieldRole.FIXED) {
                throw new SchemaValidationException("'" + name + "': '" + field + "' is fixed by '" + constructorName
                        + "', and no construction writes it (§5.5)");
            }
        }
    }

    /** Refuses a field {@code written} writes that {@code base}, named {@code baseName}, has written already. */
    static void requireWrittenOnce(String name, String baseName, IncompleteEntry base,
                                   SequencedMap<String, DataValue> written) {
        SequencedMap<String, DataValue> held = written(base);
        List<String> again = written.keySet().stream().filter(held::containsKey).toList();
        if (!again.isEmpty()) {
            throw new SchemaValidationException("'" + name + "': " + again + (again.size() == 1 ? " is" : " are")
                    + " written by '" + baseName + "' already, and a field of an incomplete entry is written once, "
                    + "whatever the value (§5.5) -- to narrow it, complete '" + baseName + "' and refine that type");
        }
    }

    /**
     * {@code !X ^ { … }}: what {@code base} writes and {@code written}, fields it has not written, adds, which leaves
     * a required field absent. One writing the last is refused, a refinement keeping what its base is.
     */
    static IncompleteEntry refined(String name, String baseName, IncompleteEntry base, DataValue payload,
                                   SequencedMap<String, DataValue> written, Map<String, Object> read, String closed) {
        List<String> absent = base.absent().stream().filter(field -> !written.containsKey(field)).toList();
        if (absent.isEmpty()) {
            throw new SchemaValidationException("'" + name + "': '!" + baseName + " ^ { ... }' writes " + base.absent()
                    + ", every required field '" + baseName + "' leaves absent, so nothing is left open and '^' keeps "
                    + "what its base is -- complete it with '!" + baseName + " { ... }'");
        }
        SequencedMap<String, DataValue> merged = new LinkedHashMap<>(written(base));
        merged.putAll(written);
        Map<String, Object> mergedRead = new LinkedHashMap<>(base.read());
        mergedRead.putAll(read);
        return new IncompleteEntry(base.constructor(), absent, record(payload, base.constructor(), merged),
                mergedRead, closedThrough(closed, base));
    }

    /**
     * The completion of {@code base} by {@code payload}: the binding record of {@code base}'s constructor writing
     * what {@code base} writes and what {@code payload} adds, every required field among them. A payload that is no
     * braced record is the value of the one required field {@code base} leaves absent, §5.6's positional form, as a
     * value standing for a constructor's one unmarked field is: {@code !nonempty text} is {@code !nonempty {
     * element_type: text }}.
     */
    static DataValue completed(String name, String baseName, IncompleteEntry base, RecordBody constructor,
                               DataValue payload) {
        SequencedMap<String, DataValue> written = braced(payload).orElseGet(() -> positional(name, baseName, base,
                payload));
        requireWritable(name, base.constructor(), constructor, written);
        requireWrittenOnce(name, baseName, base, written);
        List<String> missing = base.absent().stream().filter(field -> !written.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            throw new SchemaValidationException("'" + name + "': completing '" + baseName + "' writes every required "
                    + "field it leaves absent, and " + missing + (missing.size() == 1 ? " is" : " are") + " not "
                    + "written -- write " + (missing.size() == 1 ? "it" : "them") + " in '!" + baseName
                    + " { ... }', or leave the entry open with '!" + baseName + " ^ { ... }' (§5.5)");
        }
        SequencedMap<String, DataValue> merged = new LinkedHashMap<>(written(base));
        merged.putAll(written);
        return record(payload, base.constructor(), merged);
    }

    /**
     * The incomplete entries a construction from {@code base}, the entry named {@code closed} past its alias hops,
     * is built through: {@code closed}, then {@code base}'s own bases, nearest first.
     */
    static List<String> closedThrough(String closed, IncompleteEntry base) {
        List<String> through = new ArrayList<>(List.of(closed));
        through.addAll(base.bases());
        return List.copyOf(through);
    }

    private static SequencedMap<String, DataValue> positional(String name, String baseName, IncompleteEntry base,
                                                              DataValue payload) {
        if (base.absent().size() != 1) {
            throw new SchemaValidationException("'" + name + "': '" + baseName + "' leaves " + base.absent()
                    + " absent, and a completion is braced, '!" + baseName + " { ... }' -- the positional form '!"
                    + baseName + " value' completes only an entry leaving one required field absent (§5.5, §5.6)");
        }
        SequencedMap<String, DataValue> written = new LinkedHashMap<>();
        written.put(base.absent().getFirst(), new DataValue(List.of(), Optional.empty(), payload.coreValue()));
        return written;
    }
}
