package io.ltr8.tson.compiler.writer;

import io.ltr8.tson.encoding.TsonSchemaBinding;
import io.ltr8.tson.schema.ExternAdmission;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tags a bound value carries where it is written at a position typed by an extern ([TSON-SCHEMA] §7.9), keyed by
 * the record class and component that hold the position. A tag there is read in the enclosing scope, so the class of
 * the value names the entry it is bound to, and the tag is the enclosing scope's name reaching that entry: the extern
 * for its target, and for a subtype or a variant the name {@link ExternAdmission} admits for it.
 *
 * <p><b>A class maps to an entry by asking forward</b>, for each entry the boundary admits, what the binding a read
 * binds by answers ({@link TsonSchemaBinding}), so a write inverts exactly what the read would bind. Nothing on the
 * class names its entry.
 *
 * <p>Built once per compiled schema a writer describes, from its binding and that of every foreign schema an extern
 * reaches, so a nested boundary is tagged in the scope enclosing it; a write looks up its record and component and
 * allocates nothing.
 */
public final class ExternTags {

    /** No position typed by an extern: every tag is the class's own. */
    public static final ExternTags NONE = new ExternTags(Map.of());

    private final Map<Class<?>, Map<String, Boundary>> byRecord;

    private ExternTags(Map<Class<?>, Map<String, Boundary>> byRecord) {
        this.byRecord = byRecord;
    }

    /**
     * One boundary: the extern typing it, how a message describes it, and each class the admitted entries bind to
     * with the enclosing scope's name for it.
     */
    public record Boundary(String extern, Map<Class<?>, String> tags, String admissible) {

        /** The enclosing scope's name for {@code type}'s entry, or {@code null} where it has none. */
        public String tagFor(Class<?> type) {
            return tags.get(type);
        }
    }

    /** The boundary at component {@code component} of {@code record}, or {@code null} where none is. */
    public Boundary at(Class<?> record, String component) {
        if (byRecord.isEmpty()) {
            return null;
        }
        Map<String, Boundary> components = byRecord.get(record);
        return components == null ? null : components.get(component);
    }

    /**
     * Every boundary of the schemas {@code scopes} answer for -- a compiled schema's and those of every schema its
     * externs reach, each as its own compile bound it. A record nothing binds holds no boundary a bound object could
     * reach.
     */
    public static ExternTags of(Collection<TsonSchemaBinding> scopes) {
        Map<Class<?>, Map<String, Boundary>> byRecord = new HashMap<>();
        for (TsonSchemaBinding binding : scopes) {
            TsonLinkedSchema scope = binding.schema();
            Map<String, Boundary> boundaries = new HashMap<>();
            scope.schema().entries().forEach((name, definition) -> {
                if (!(definition instanceof RecordBody record) || record.isParametric()) {
                    return;
                }
                Class<?> bound = bound(binding, name);
                if (bound == null) {
                    return;
                }
                for (Map.Entry<String, RecordField> field : record.fields().entrySet()) {
                    String extern = externOf(field.getValue().type(), scope);
                    if (extern != null) {
                        Boundary boundary = boundaries.computeIfAbsent(extern, at -> boundary(scope, at, binding));
                        byRecord.computeIfAbsent(bound, key -> new HashMap<>()).putIfAbsent(field.getKey(), boundary);
                    }
                }
            });
        }
        Map<Class<?>, Map<String, Boundary>> fixed = new HashMap<>();
        byRecord.forEach((type, components) -> fixed.put(type, Map.copyOf(components)));
        return fixed.isEmpty() ? NONE : new ExternTags(Map.copyOf(fixed));
    }

    /**
     * The extern a value of {@code type} crosses, past its alias chain, or through the element or value type of a
     * container written in place, each element being a value at the boundary; {@code null} where it crosses none.
     */
    private static String externOf(TypeExpr type, TsonLinkedSchema scope) {
        return switch (type) {
            case TypeRef reference -> {
                String terminal = scope.terminal(reference.name());
                yield scope.schema().entries().get(terminal) instanceof Extern ? terminal : null;
            }
            case TypeInline inline -> switch (inline.of()) {
                case ArrayBody array -> externOf(array.elementType(), scope);
                case MapBody map -> externOf(map.valueType(), scope);
                default -> null;
            };
        };
    }

    private static Boundary boundary(TsonLinkedSchema scope, String extern, TsonSchemaBinding binding) {
        ExternAdmission admission = ExternAdmission.of(scope, extern);
        Map<Class<?>, String> tags = new LinkedHashMap<>();
        admission.admitted().forEach((written, reach) -> {
            if (!reach.same()) {
                return;
            }
            Class<?> bound = bound(binding, written);
            if (bound != null) {
                tags.putIfAbsent(bound, extern);
            }
        });
        admission.admitted().forEach((written, reach) -> {
            Class<?> bound = bound(binding, written);
            if (bound != null) {
                tags.putIfAbsent(bound, written);
            }
        });
        return new Boundary(extern, Map.copyOf(tags), String.join(" | ", admission.admitted().keySet()));
    }

    /** The class {@code binding} binds entry {@code name} to, as a read binds it, or {@code null}. */
    private static Class<?> bound(TsonSchemaBinding binding, String name) {
        return binding.get(name).<Class<?>>map(entry -> entry.dataClass().typeClass()).orElse(null);
    }
}
