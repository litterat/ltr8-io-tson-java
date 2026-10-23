package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.resolver.KindMembers;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLoader;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Ref;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The names a value entry holds -- a {@link Namespace}, a value of a namespace type -- resolved and judged by their slots
 * (SPEC-FEEDBACK.md #13, #24): a value's qualified name at a slot typed {@code ref<T>}, held as a {@link Ref} the
 * linker delivers into or a {@link TypeRef} carrying the name alone; and a type's name at a slot typed by a kind,
 * judged a member ({@link #judgeMember}).
 *
 * <p><b>Fed by the read, not by the host.</b> The references judged are the ones the resolver's read produced by
 * name, each with its slot ({@link TsonSchema#valuesByName()}). The host class declares nothing for them, so none
 * can be missed.
 *
 * <p><b>The grammar is judged here, where the name is resolved.</b> The read keeps any unquoted token; this splits
 * it at each {@code .} and requires each segment to be a [TSON-DATA] §7.7 identifier ({@link #segments}), so
 * {@code orders.1x}, {@code .orders} and {@code orders.} are refused as no qualified name before anything is looked
 * up. A reference carrying arguments is no value by name.
 *
 * <p><b>Two layers meet here.</b> The slot's type is the meta-schema's to declare, and arrives with the name: the
 * reader that read the name was built from the slot's declared type and recorded it beside the name ({@link
 * TsonSchema.SlotType}), a kind's {@code of} or a reference's {@code T} in the governing meta's namespace, whatever the
 * host class holds there. The name is the governed schema's to resolve: its first segment through this schema's own
 * entries and imports, alias hops followed ([TSON-SCHEMA] §8.3), never through the meta's namespace (§3.3.2); each
 * further segment selects in the value reached -- a key of a namespace value, a field of a record, never a position in
 * a list -- and a type entry admits none. An open entry is no referent at all: a template denotes no value until it is
 * applied. The referent's declared type is then judged against the slot's by §7.2's IS-A, comparing canonical
 * identities: an entry's declared type is the constructor that built its body, a field's is its type, and a map value's
 * is the map's value type. Found first, judged after, never a choice among candidates.
 *
 * <p><b>An entry's constructor</b> is its value's tag, as its facts record it, where that names a constructor of the
 * declaring schema's meta layer, and otherwise the kernel constructor its body mirrors ({@code @Typename}): a record
 * declaration or a composition is built by {@code record}, and {@code uuid => !uuid_type {}} by {@code uuid_type}.
 * Each type is looked up in the namespace of the meta layer that governs the schema declaring it, so an imported
 * referent is judged by its own meta's vocabulary and still compared by identity.
 *
 * <p><b>A namespace value is entered by key, and only there is the value walked.</b> A namespace entry holds the
 * map its constructor describes, so a segment selects a key of it and the value reached is of the constructor's
 * {@code value_type}; a value of a namespace type reached that way is the map the read built for it, entered the
 * same way. A segment selecting a field of a record value is followed by the record's declared fields alone: the
 * value is the host's object, which this walk never reads, so a name whose walk passes through a field is judged by
 * type and then reported as this library's gap ({@link UnsupportedOperationException}), since the value it reaches
 * is neither found nor delivered.
 *
 * <p>Every refusal is a {@link SchemaValidationException} naming the step that failed.
 */
final class ValuesByName {

    private final Map<String, Top> merged;
    private final Map<String, TsonSchema.EntryFacts> facts;
    private final Map<String, String> origins;
    private final String ownIdentity;
    private final TsonLinkedSchema governingMeta;
    private final TsonSchemaLoader loader;

    ValuesByName(TsonSchema schema, Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                 Map<String, String> origins, TsonLinkedSchema governingMeta, TsonSchemaLoader loader) {
        this.merged = merged;
        this.facts = facts;
        this.origins = origins;
        this.ownIdentity = CanonicalIdentity.canonicalize(schema.id());
        this.governingMeta = governingMeta;
        this.loader = loader;
    }

    /** A type named in one meta layer's namespace: where its name is looked up, and the name. */
    private record Typed(TsonLinkedSchema space, String name) {

        private Namespace<Top> entries() {
            return space.schema().entries();
        }

        String terminal() {
            return ReferenceChain.terminal(name, entries()::get);
        }

        Top definition() {
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

        /**
         * What a value of this type is read as: the map a namespace type's value is ({@link
         * TsonLinkedSchema#namespace}), and the definition otherwise.
         */
        Top body() {
            return space.namespace(terminal()).<Top>map(MapBody.Namespace::map).orElseGet(this::definition);
        }

        /**
         * Whether this type is a namespace type, IS-A the kernel's {@code namespace}: a map whose keys name its
         * entries, which is the one map a qualified name enters ({@code method_map => namespace & { ... }}); a
         * {@code {K => V}} sugar map is built with {@code map} and is not.
         */
        boolean isNamespace() {
            return space.namespace(terminal()).isPresent();
        }

        /** §7.2: this type is {@code wanted}, or lists it among its supertypes. */
        boolean isA(Typed wanted) {
            String target = wanted.identity();
            if (identity().equals(target)) {
                return true;
            }
            Top definition = definition();
            return definition != null && TypeChain.supertypesOf(definition).stream()
                    .anyMatch(supertype -> of(supertype).identity().equals(target));
        }
    }

    /**
     * Resolves and judges every reference by name the read collected from the body of local entry {@code entryName}
     * ({@link TsonSchema#valuesByName()}).
     */
    void check(String entryName, List<TsonSchema.ValueByName> held, Consumer<SchemaValidationException> refused,
               Consumer<UnsupportedOperationException> gap) {
        for (TsonSchema.ValueByName value : held) {
            String where = "'" + entryName + "' at " + pointer(value.slot());
            try {
                judge(where, value.reference(), value.type(), value.cell());
            } catch (SchemaValidationException e) {
                refused.accept(e);
            } catch (UnsupportedOperationException e) {
                gap.accept(e); // a name this library does not yet follow: reported as the gap it is, never thrown
            }
        }
    }

    /**
     * The segments of the qualified name {@code reference} writes: its {@code name} split at each {@code .}, each a
     * §7.7 identifier. Refused where the name has an empty segment, a segment that is no identifier, or arguments.
     */
    static List<String> segments(String where, TypeRef reference) {
        if (!reference.arguments().isEmpty()) {
            throw new SchemaValidationException(where + " holds '" + reference.name() + "' with arguments, and a "
                    + "value by name is a qualified name alone");
        }
        List<String> segments = List.of(reference.name().split("\\.", -1));
        for (String segment : segments) {
            Optional<String> violation = segment.isEmpty() ? Optional.of("a segment is empty")
                    : IdentifierGrammar.validate(segment);
            if (violation.isPresent()) {
                throw new SchemaValidationException(where + " holds '" + reference.name() + "', which is no "
                        + "qualified name: each segment between the dots is an identifier, and " + (segment.isEmpty()
                        ? "one is empty" : "'" + segment + "' is not -- " + violation.get()));
            }
        }
        return segments;
    }

    private void judge(String where, TypeRef reference, TsonSchema.SlotType slot, Optional<Ref<?>> cell) {
        if (slot.role() == TsonSchema.SlotType.Role.APPLICATION) {
            judgeApplication(where, reference);
            return;
        }
        List<String> segments = segments(where, reference);
        String name = reference.name();
        String head = segments.getFirst();
        if (!merged.containsKey(head)) {
            throw new SchemaValidationException(where + " names '" + name + "', and '" + head + "' is no "
                    + (slot.role() == TsonSchema.SlotType.Role.KIND && segments.size() == 1
                            ? "type declared in this schema or its imports"
                            : "entry of this schema or its imports -- the first segment names the entry a value is "
                                    + "reached through, and a member is reached through its entry"));
        }
        String terminal = ReferenceChain.terminal(head, merged);
        Top referent = merged.get(terminal);
        if (referent == null) {
            throw new SchemaValidationException(where + " names '" + name + "', and the alias '" + head
                    + "' reaches no entry");
        }
        String entry = terminal.equals(head) ? "'" + head + "'" : "'" + head + "' (an alias of '" + terminal + "')";
        if (OpenEntry.isOpen(referent)) {
            throw new SchemaValidationException(where + " names '" + name + "', and " + entry + " is an open "
                    + "entry: a template denotes no value until it is applied (§5.10), so nothing is there to name");
        }
        if (slot.role() == TsonSchema.SlotType.Role.KIND) {
            judgeMember(where, name, segments, slot, referent, entry);
            return;
        }
        Typed wanted = new Typed(governingMeta, slot.of());
        TsonLinkedSchema declarersMeta = metaOf(terminal);
        Typed at = constructorOf(TsonSchemaLinker.constructorOf(terminal, referent, facts).orElseThrow(), referent,
                declarersMeta);
        if (segments.size() == 1) {
            if (!at.isA(wanted)) {
                throw new SchemaValidationException(where + " wants " + a(wanted.name()) + ", and " + entry
                        + " is an entry whose body is built by '" + at.name() + "', which is no '" + wanted.name()
                        + "'");
            }
            cell.ifPresent(c -> c.deliver(referent));
            return;
        }
        if (!(referent instanceof Namespace<?> namespace)) {
            throw new SchemaValidationException(where + " names '" + name + "', and " + entry + " is a "
                    + "type: a type entry admits no further segment, so '" + segments.get(1) + "' selects in no value");
        }
        Object value = namespace;
        String throughField = null;
        String reached = head;
        for (String segment : segments.subList(1, segments.size())) {
            Top body = at.body();
            switch (body) {
                case RecordBody record -> {
                    RecordField field = record.fields().get(segment);
                    if (field == null) {
                        throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                + "' is " + a(at.name()) + ", which has no field '" + segment + "' -- it declares "
                                + String.join(", ", record.fields().keySet()));
                    }
                    if (throughField == null) {
                        throughField = reached + '.' + segment;
                    }
                    value = null;
                    at = at.of(field.type().name());
                }
                case MapBody map -> {
                    if (!at.isNamespace()) {
                        throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                + "' is " + a(at.name()) + ", a map that is no namespace, so it is not entered by "
                                + "name -- a segment selects a key of a namespace value or a field of a record value");
                    }
                    if (value != null) {
                        Map<?, ?> entries = asMap(value, reached);
                        Optional<Object> found = keyed(entries, segment);
                        if (found.isEmpty()) {
                            throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                    + "' has no key '" + segment + "' -- it holds " + keysOf(entries));
                        }
                        value = found.get();
                    }
                    at = at.of(map.valueType().name());
                }
                case ArrayBody ignored -> throw new SchemaValidationException(where + " names '" + name + "', and '"
                        + reached + "' is a list, whose elements have no names -- a segment selects a field of a "
                        + "record or a key of a map, never a position");
                case null, default -> throw new SchemaValidationException(where + " names '" + name + "', and '"
                        + reached + "' is " + a(at.name()) + ", which has no member selected by name -- a "
                        + "segment selects a field of a record or a key of a map");
            }
            reached = reached + '.' + segment;
        }
        if (!at.isA(wanted)) {
            throw new SchemaValidationException(where + " wants " + a(wanted.name()) + ", and '" + name + "' is "
                    + a(at.name()) + ", which is no '" + wanted.name() + "'");
        }
        if (throughField != null) {
            throw new UnsupportedOperationException(where + " names '" + name + "', which reaches its value through "
                    + "'" + throughField + "', a field of a record value -- this library enters a value by the keys of "
                    + "a namespace value and does not yet read a record value's field, so the value is not found");
        }
        Object delivered = value;
        cell.ifPresent(c -> c.deliver(delivered));
    }

    /**
     * A kind-typed slot holds a type's name, one segment, and the name is a member of the kind or refused
     * ({@link KindMembers}): the referent's construction chain reaches the kind's {@code of}, and the referent is no
     * constructor. A namespace entry is refused as a value, however many segments name it. A type reached through
     * a namespace of types is not yet a spelling this library reads.
     */
    private void judgeMember(String where, String name, List<String> segments, TsonSchema.SlotType slot,
                             Top referent, String entry) {
        String wants = where + " wants the name of a type built with '" + slot.of() + "' (a member of '"
                + slot.name() + "'), and ";
        if (segments.size() > 1 && referent instanceof Namespace<?> namespace) {
            throw new SchemaValidationException(wants + "'" + name + "' reaches into " + entry + ", which is "
                    + aValueNoType(namespace) + " -- and what a further segment selects in a value is a value too");
        }
        if (segments.size() > 1) {
            throw new UnsupportedOperationException(where + " names '" + name + "' at a position typed by the kind '"
                    + slot.name() + "', which holds a type's name -- this library does not yet read a type through "
                    + "a namespace of types, so the name is one segment");
        }
        if (KindMembers.isMember(name, slot.of(), merged::get,
                named -> TsonSchemaLinker.constructorOf(named, merged.get(named), facts),
                governingMeta.schema().entries()::get)) {
            return;
        }
        if (referent instanceof Namespace<?> namespace) {
            throw new SchemaValidationException(wants + entry + " is " + aValueNoType(namespace));
        }
        String terminal = ReferenceChain.terminal(name, merged);
        throw new SchemaValidationException(wants + entry + " is an entry whose body is built by '"
                + TsonSchemaLinker.constructorOf(terminal, referent, facts).orElse("nothing") + "', which is no '"
                + slot.of() + "'");
    }

    /**
     * A {@code type_ref}-typed slot of a value body holds the application form: a name that resolves, with as many
     * arguments as the entry it names declares parameters, each argument the same; and never a value (§4.1).
     * Judged by resolution and arity alone, as a {@code type_ref} slot is everywhere.
     */
    private void judgeApplication(String where, TypeRef reference) {
        String name = reference.name();
        String terminal = ReferenceChain.terminal(name, merged);
        Top referent = merged.get(terminal);
        if (referent == null) {
            throw new SchemaValidationException(where + " names '" + name + "', which is no type declared in this "
                    + "schema or its imports");
        }
        if (referent instanceof Namespace<?> namespace) {
            throw new SchemaValidationException(where + " names '" + name + "', which is " + aValueNoType(namespace));
        }
        int parameters = OpenEntry.isOpen(referent) ? OpenEntry.parameterNames(referent).size() : 0;
        if (reference.arguments().size() != parameters) {
            throw new SchemaValidationException(where + " applies '" + name + "' to " + reference.arguments().size()
                    + " argument" + (reference.arguments().size() == 1 ? "" : "s") + ", and it declares "
                    + parameters + " parameter" + (parameters == 1 ? "" : "s"));
        }
        for (io.ltr8.tson.schema.meta.TypeArgument argument : reference.arguments()) {
            if (argument instanceof io.ltr8.tson.schema.meta.TypeArgument.Ref nested) {
                judgeApplication(where, nested.ref());
            }
        }
    }

    /** What a namespace entry is, where a type is wanted: a value, which nothing can be typed by (§4.1). */
    private static String aValueNoType(Namespace<?> namespace) {
        return "a value of the namespace type '" + namespace.type() + "', a map of names -- a value and not a type, so "
                + "nothing can be typed by it";
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
     * The constructor that built {@code definition}'s body: its tag where that names a constructor in {@code meta}'s
     * namespace, and otherwise the kernel constructor the body's class mirrors.
     */
    private static Typed constructorOf(String tag, Top definition, TsonLinkedSchema meta) {
        Typed source = new Typed(meta, tag);
        Top constructor = source.definition();
        if (constructor != null && meta.isConstructor(tag, constructor)) {
            return source;
        }
        return new Typed(meta, TsonCompiledMetaSchema.typenameOf(definition));
    }

    /**
     * The map a value of a namespace type holds: a {@link Namespace}'s entries, or the map the read built for a value
     * of a namespace type held under a key of another.
     */
    private static Map<?, ?> asMap(Object value, String reached) {
        if (value instanceof Namespace<?> namespace) {
            return namespace.entries();
        }
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException("'" + reached + "' is a value of a namespace type, read as a "
                + value.getClass().getName() + " rather than a map");
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
