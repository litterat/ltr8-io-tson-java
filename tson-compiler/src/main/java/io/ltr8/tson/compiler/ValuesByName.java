package io.ltr8.tson.compiler;

import io.ltr8.annotation.Field;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLoader;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.QualifiedName;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The values a {@link Data} body holds by name, resolved and judged (SPEC-FEEDBACK.md #13) -- the linker's half
 * of a slot whose declared type is a constructor or a record and whose value is written as a {@link
 * QualifiedName}.
 *
 * <p><b>Two layers meet here.</b> The slot's type is the meta-schema's to declare, and is read off the
 * constructor's resolved fields by walking {@link Data.Named#slot()} in the governing meta's namespace. The name is
 * the governed schema's to resolve: its first segment through this schema's own entries and imports, alias hops
 * followed ([TSON-SCHEMA] §8.3), never through the meta's namespace (§3.3.2); each further segment selects in the
 * value reached -- a field of a record, then a key of a map, never a position in a list -- and a type entry admits
 * none. An open entry is no referent at all: a template denotes no value until it is applied. The referent's
 * declared type is then judged against the slot's by §7.2's IS-A, comparing canonical identities: an entry's
 * declared type is the constructor that built its body, a field's is its type, and a map value's is the map's value
 * type. Found first, judged after, never a choice among candidates.
 *
 * <p><b>An entry's constructor</b> is its {@code source} where that names a constructor of the declaring schema's
 * meta layer, and otherwise the kernel constructor its body mirrors ({@code @Typename}): a record declaration or a
 * composition is built by {@code record}, and {@code uuid => !uuid_type {}} by {@code uuid_type}. Each type is
 * looked up in the namespace of the meta layer that governs the schema declaring it, so an imported referent is
 * judged by its own meta's vocabulary and still compared by identity.
 *
 * <p><b>A selected value is reached through the body's record components</b>, named as binding names them (a
 * component's {@code @Field} rename, else its own name), an {@code Optional} unwrapped, and a map entered by key.
 * A map whose keys are not names -- its key type no identifier family or enum -- cannot be entered. A body whose
 * class is not a Java record cannot be entered at all, which is this library's gap rather than the schema's
 * ({@link UnsupportedOperationException}).
 *
 * <p>Every refusal is a {@link SchemaValidationException} naming the step that failed; a body whose declared slots
 * do not fit its constructor is the reading application's mistake ({@link BindMismatchException}).
 */
final class ValuesByName {

    private final Map<String, TypeDefinition> merged;
    private final Map<String, String> origins;
    private final String ownIdentity;
    private final TsonLinkedSchema governingMeta;
    private final TsonSchemaLoader loader;

    ValuesByName(TsonSchema schema, Map<String, TypeDefinition> merged, Map<String, String> origins,
                 TsonLinkedSchema governingMeta, TsonSchemaLoader loader) {
        this.merged = merged;
        this.origins = origins;
        this.ownIdentity = CanonicalIdentity.canonicalize(schema.id());
        this.governingMeta = governingMeta;
        this.loader = loader;
    }

    /** A type named in one meta layer's namespace: where its name is looked up, and the name. */
    private record Typed(TsonLinkedSchema space, String name) {

        private Map<String, TypeDefinition> entries() {
            return space.schema().entries();
        }

        private String terminal() {
            return ReferenceChain.terminal(name, entries());
        }

        TypeDefinition definition() {
            return entries().get(terminal());
        }

        Typed of(String typeName) {
            return new Typed(space, typeName);
        }

        /**
         * Declaring schema and entry, which is what two spellings of one type agree on. Every entry of a linked
         * namespace records its canonical origin, so the origin is compared as recorded.
         */
        String identity() {
            String terminal = terminal();
            return space.originOf(terminal) + "#" + terminal;
        }

        /** §7.2: this type is {@code wanted}, or lists it among its supertypes. */
        boolean isA(Typed wanted) {
            String target = wanted.identity();
            if (identity().equals(target)) {
                return true;
            }
            TypeDefinition definition = definition();
            return definition != null && definition.supertypes().stream()
                    .anyMatch(supertype -> of(supertype).identity().equals(target));
        }
    }

    /** Resolves and judges every name {@code data}, the body of local entry {@code entryName}, declares. */
    void check(String entryName, TypeDefinition definition, Data data, Consumer<SchemaValidationException> refused) {
        List<Data.Named> names = data.names();
        if (names == null) {
            throw new BindMismatchException(data.getClass().getName() + " returned null from names() for '"
                    + entryName + "' -- return List.of() for a body that holds no value by name");
        }
        if (names.isEmpty()) {
            return;
        }
        Typed constructor = constructorOf(definition, governingMeta);
        for (Data.Named named : names) {
            Typed slot = slotType(entryName, data, constructor, named.slot());
            try {
                judge("'" + entryName + "' at " + pointer(named.slot()), named.name(), slot);
            } catch (SchemaValidationException e) {
                refused.accept(e);
            }
        }
    }

    /** The declared type of the slot {@code steps} reach from the body of {@code constructor}. */
    private static Typed slotType(String entryName, Data data, Typed constructor, List<String> steps) {
        Typed at = constructor;
        for (String step : steps) {
            TypeDefinition definition = at.definition();
            Top body = definition == null ? null : definition.body();
            at = switch (body) {
                case RecordBody record when record.fields().containsKey(step) ->
                        at.of(record.fields().get(step).type().name());
                case MapBody map -> at.of(map.valueType().name());
                case ArrayBody array -> at.of(array.elementType().name());
                case null, default -> throw new BindMismatchException(data.getClass().getName() + " declares a "
                        + "name at " + pointer(steps) + " of '" + entryName + "', and '" + constructor.name()
                        + "' declares no such slot -- names() states each slot as the path of field names, map "
                        + "keys and array indexes from the body to the field the name fills");
            };
        }
        return at;
    }

    private void judge(String where, QualifiedName name, Typed slot) {
        String head = name.head();
        if (!merged.containsKey(head)) {
            throw new SchemaValidationException(where + " names '" + name + "', and '" + head + "' is no entry "
                    + "of this schema or its imports -- the first segment names the entry a value is reached "
                    + "through, and a member is reached through its entry");
        }
        String terminal = ReferenceChain.terminal(head, merged);
        TypeDefinition referent = merged.get(terminal);
        if (referent == null) {
            throw new SchemaValidationException(where + " names '" + name + "', and the alias '" + head
                    + "' reaches no entry");
        }
        String entry = terminal.equals(head) ? "'" + head + "'" : "'" + head + "' (an alias of '" + terminal + "')";
        if (referent.body() instanceof TemplateBody) {
            throw new SchemaValidationException(where + " names '" + name + "', and " + entry + " is an open "
                    + "entry: a template denotes no value until it is applied (§5.10), so nothing is there to name");
        }
        TsonLinkedSchema declarersMeta = metaOf(terminal);
        Typed at = constructorOf(referent, declarersMeta);
        List<String> segments = name.segments();
        if (segments.size() == 1) {
            if (!at.isA(slot)) {
                throw new SchemaValidationException(where + " wants " + a(slot.name()) + ", and " + entry
                        + " is an entry whose body is built by '" + at.name() + "', which is no '" + slot.name()
                        + "'");
            }
            return;
        }
        if (!(referent.body() instanceof Data held)) {
            throw new SchemaValidationException(where + " names '" + name + "', and " + entry + " is a type: a "
                    + "type entry admits no further segment, so '" + segments.get(1) + "' selects in no value");
        }
        Object value = held;
        String reached = head;
        for (String segment : segments.subList(1, segments.size())) {
            TypeDefinition definition = at.definition();
            Top body = definition == null ? null : definition.body();
            switch (body) {
                case RecordBody record -> {
                    RecordField field = record.fields().get(segment);
                    if (field == null) {
                        throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                + "' is " + a(at.name()) + ", which has no field '" + segment + "' -- it declares "
                                + String.join(", ", record.fields().keySet()));
                    }
                    value = component(value, segment);
                    if (value == null) {
                        throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                + "' holds no value for its field '" + segment + "'");
                    }
                    at = at.of(field.type().name());
                }
                case MapBody map -> {
                    TypeDefinition keys = at.of(map.keyType().name()).definition();
                    if (keys == null || !(keys.body() instanceof IdentifierType || keys.body() instanceof EnumBody)) {
                        throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                + "' is a map keyed by '" + map.keyType().name() + "', whose keys are not names, "
                                + "so it cannot be entered by name");
                    }
                    Map<?, ?> entries = asMap(value, reached);
                    Optional<Object> found = keyed(entries, segment);
                    if (found.isEmpty()) {
                        throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                + "' has no key '" + segment + "' -- it holds " + keysOf(entries));
                    }
                    value = found.get();
                    at = at.of(map.valueType().name());
                }
                case ArrayBody ignored -> throw new SchemaValidationException(where + " names '" + name + "', and '"
                        + reached + "' is a list, whose elements have no names -- a segment selects a field of a "
                        + "record or a key of a map, never a position");
                case null, default -> throw new SchemaValidationException(where + " names '" + name + "', and '"
                        + reached + "' is " + a(at.name()) + ", which has no member selected by name -- a "
                        + "segment selects a field of a record or a key of a map");
            }
            reached = reached + QualifiedName.SEPARATOR + segment;
        }
        if (!at.isA(slot)) {
            throw new SchemaValidationException(where + " wants " + a(slot.name()) + ", and '" + name + "' is "
                    + a(at.name()) + ", which is no '" + slot.name() + "'");
        }
    }

    /** The meta layer governing the schema that declared {@code entry}: this schema's, or an import's own. */
    private TsonLinkedSchema metaOf(String entry) {
        String origin = origins.getOrDefault(entry, ownIdentity);
        if (origin.equals(ownIdentity) || loader == null) {
            return governingMeta;
        }
        return loader.load(origin)
                .flatMap(declarer -> loader.load(CanonicalIdentity.canonicalize(declarer.schema().meta())))
                .orElse(governingMeta);
    }

    /**
     * The constructor that built {@code definition}'s body: its {@code source} where that names a constructor in
     * {@code meta}'s namespace, and otherwise the kernel constructor the body's class mirrors.
     */
    private static Typed constructorOf(TypeDefinition definition, TsonLinkedSchema meta) {
        if (definition.source().isPresent() && definition.source().get().arguments().isEmpty()) {
            Typed source = new Typed(meta, definition.source().get().name());
            TypeDefinition constructor = source.definition();
            if (constructor != null && constructor.supertypes().contains("top")) {
                return source;
            }
        }
        return new Typed(meta, TsonCompiledMetaSchema.typenameOf(definition.body()));
    }

    /** The value of field {@code name} of {@code value}, through its record component; {@code null} if absent. */
    private static Object component(Object value, String name) {
        RecordComponent[] components = value.getClass().getRecordComponents();
        if (components == null) {
            throw new UnsupportedOperationException("a value held by name is entered through record components, "
                    + "and " + value.getClass().getName() + " is not a record");
        }
        for (RecordComponent component : components) {
            if (!name.equals(wireName(component))) {
                continue;
            }
            try {
                Object held = component.getAccessor().invoke(value);
                return held instanceof Optional<?> optional ? optional.orElse(null) : held;
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new BindMismatchException("component '" + component.getName() + "' of "
                        + value.getClass().getName() + " could not be read to select field '" + name + "': " + e, e);
            }
        }
        throw new BindMismatchException(value.getClass().getName() + " has no component for field '" + name + "'");
    }

    /** The field name a component binds under, as binding reads it: its {@code @Field} rename, else its own. */
    private static String wireName(RecordComponent component) {
        Field renamed = component.getAnnotation(Field.class);
        if (renamed == null) {
            renamed = component.getAccessor().getAnnotation(Field.class);
        }
        return renamed != null && !renamed.value().isEmpty() ? renamed.value() : component.getName();
    }

    private static Map<?, ?> asMap(Object value, String reached) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new BindMismatchException("'" + reached + "' is a map, and the component holding it is a "
                + value.getClass().getName());
    }

    /** The value under the key spelled {@code segment}: a {@code String} key directly, any other by its text. */
    private static Optional<Object> keyed(Map<?, ?> map, String segment) {
        if (map.containsKey(segment)) {
            return Optional.ofNullable(map.get(segment));
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (String.valueOf(entry.getKey()).equals(segment)) {
                return Optional.ofNullable(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private static String keysOf(Map<?, ?> map) {
        return map.isEmpty() ? "none" : String.join(", ", map.keySet().stream().map(String::valueOf).toList());
    }

    /** A type named with its article, as a message says it: {@code a 'method'}, {@code an 'interface'}. */
    private static String a(String typeName) {
        return ("aeiou".indexOf(typeName.charAt(0)) >= 0 ? "an '" : "a '") + typeName + "'";
    }

    /** A slot as an RFC 6901 pointer into the body, as a diagnostic path is written. */
    private static String pointer(List<String> steps) {
        StringBuilder pointer = new StringBuilder();
        for (String step : steps) {
            pointer.append('/').append(step.replace("~", "~0").replace("/", "~1"));
        }
        return pointer.toString();
    }
}
