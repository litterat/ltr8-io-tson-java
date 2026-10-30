package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.resolver.KindMembers;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.tree.TsonValue;
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
import java.util.function.Function;

/**
 * The names a value entry holds ({@link ValueEntry}) resolved and judged by their slots
 * ([TSON-SCHEMA] §5.12, §4.2): a value's qualified name at a slot typed {@code ref<T>}, held as a {@link Ref} the
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
 * <p><b>One namespace holds both sides.</b> The slot's type arrives with the name: the reader that read the name was built
 * from the slot's declared type and recorded it beside the name ({@link TsonSchema.SlotType}), the slot's reference's {@code
 * of}, whatever the host class holds there. What {@code of} names decides what the name is, judged here over this namespace
 * whatever the read guessed: a constructor's values are types, so the name is a type's and a member or refused ({@link
 * KindMembers}); any other type's values are values. Both it and the name resolve in this schema's one namespace, its own
 * entries and its import closure: the first segment, alias hops followed ([TSON-SCHEMA] §8.3), and each further segment
 * selects a key of a value of a named product -- a namespace value's key, or a record value's field, the record being a
 * namespace whose keys its type fixes; never a position in a list or a key of a map that is no namespace -- and a type entry
 * admits none. An open entry is no referent at all: a template denotes no value until it is applied. The referent's declared
 * type is then judged against the slot's by §7.2's IS-A, comparing canonical identities: an entry's declared type is the
 * constructor that built its body, a field's is its type, and a map value's is the map's value type. Found first, judged
 * after, never a choice among candidates.
 *
 * <p><b>An entry's constructor</b> is its value's tag, as its facts record it, where that names a constructor, and
 * otherwise the kernel constructor its body mirrors ({@code @Typename}): a record declaration or a composition is
 * built by {@code record}, and {@code uuid => !uuid_type {}} by {@code uuid_type}. An imported referent's constructor
 * is in the closure that declared it, which this schema's closure contains, so every type is looked up in the one
 * namespace and compared by identity, declaring schema and name.
 *
 * <p><b>The value is walked beside its type.</b> A value entry holds a value of its type: of a namespace type, the map
 * it describes, so a segment selects a key of it and the value reached is of the namespace type's {@code value_type};
 * of a record type, a record, so a segment selects a field. A segment selecting a field of a bound record value reads
 * that field of the host object the read bound ({@link #fieldOf}), through the descriptor of the bind context it was
 * bound under, by the field's wire name, so the walk agrees with the binding on what the field is called and where it
 * lives; it never reflects over the host's class itself. A value no class binds is held as a tree, and a segment
 * selects in the tree by key or field name. A field the record declares and the value does not hold is refused as
 * not present, after the referent's type is judged, so a name wrong by type is told so whatever the value holds.
 *
 * <p>Every refusal is a {@link SchemaValidationException} naming the step that failed.
 */
final class ValuesByName {

    private final Map<String, Top> merged;
    private final Map<String, TsonSchema.EntryFacts> facts;
    private final Space space;
    private final DataBindContext values;

    /**
     * @param values the bind context the schema's namespace values were bound under, which a field step reads a
     *               record value through; {@code null} where the link was given none
     */
    ValuesByName(TsonSchema schema, Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                 Map<String, String> origins, DataBindContext values) {
        this.merged = merged;
        this.facts = facts;
        String ownIdentity = CanonicalIdentity.canonicalize(schema.id());
        this.space = new Space(merged, name -> origins.getOrDefault(name, ownIdentity));
        this.values = values;
    }

    /** The schema's one namespace: its entries, and where each was declared. */
    private record Space(Map<String, Top> entries, Function<String, String> originOf) {

        /** Whether {@code name} is a constructor, IS-A the kernel's {@code top} ({@link EntryKind#isConstructor}). */
        boolean isConstructor(String name, Top value) {
            return EntryKind.isConstructor(name, value, entry -> TsonBundledSchemas.isMetaKernel(originOf.apply(entry)));
        }
    }

    /** A type named in the schema's namespace. */
    private record Typed(Space space, String name) {

        String terminal() {
            return AliasChain.terminal(name, space.entries()::get);
        }

        Top definition() {
            return space.entries().get(terminal());
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
            return space.originOf().apply(terminal) + "#" + terminal;
        }

        Top body() {
            return definition();
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
        List<String> segments = segments(where, reference);
        String name = reference.name();
        String head = segments.getFirst();
        // What the slot's `of` names decides which names it holds, judged here over the final namespace whatever
        // the read guessed: a constructor's values are types, and any other type's values are values.
        boolean namesTypes = KindMembers.namesTypes(slot.of(), merged::get);
        if (!merged.containsKey(head)) {
            throw new SchemaValidationException(where + " names '" + name + "', and '" + head + "' is no "
                    + (namesTypes && segments.size() == 1
                            ? "type declared in this schema or its imports"
                            : "entry of this schema or its imports -- the first segment names the entry a value is "
                                    + "reached through, and a member is reached through its entry"));
        }
        String terminal = AliasChain.terminal(head, merged);
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
        if (namesTypes) {
            judgeMember(where, name, segments, slot, referent, entry);
            return;
        }
        Typed wanted = new Typed(space, slot.of());
        Typed at = constructorOf(TsonSchemaLinker.constructorOf(terminal, referent, facts).orElseThrow(), referent,
                space);
        if (segments.size() == 1) {
            if (!at.isA(wanted)) {
                throw new SchemaValidationException(where + " wants " + a(wanted.name()) + ", and " + entry
                        + " is an entry whose body is built by '" + at.name() + "', which is no '" + wanted.name()
                        + "'");
            }
            cell.ifPresent(c -> c.deliver(referent instanceof ValueEntry value ? value.value() : referent));
            return;
        }
        if (!(referent instanceof ValueEntry valueEntry)) {
            throw new SchemaValidationException(where + " names '" + name + "', and " + entry + " is a "
                    + "type: a type entry admits no further segment, so '" + segments.get(1) + "' selects in no value");
        }
        Object value = valueEntry.value();
        String absent = null;
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
                    if (value != null) {
                        value = value instanceof TsonValue tree ? present(tree.get(segment))
                                : fieldOf(value, segment, where + " names '" + name + "', and '" + reached + '.'
                                        + segment + "'");
                        if (value == null) {
                            absent = "'" + reached + "' is " + a(at.name()) + " whose field '" + segment + "' is "
                                    + "not present in the value";
                        }
                    }
                    at = at.of(field.type().name());
                }
                case MapBody ignored -> throw new SchemaValidationException(where + " names '" + name + "', and '"
                        + reached + "' is " + a(at.name()) + ", a map that is no namespace, so it is not entered by "
                        + "name -- a segment selects a key of a namespace value or a field of a record value");
                case NamespaceBody namespace -> {
                    if (value != null) {
                        Optional<Object> found = keyed(value, segment, reached);
                        if (found.isEmpty()) {
                            throw new SchemaValidationException(where + " names '" + name + "', and '" + reached
                                    + "' has no key '" + segment + "' -- it holds " + keysOf(value, reached));
                        }
                        value = found.get();
                    }
                    at = at.of(namespace.valueType().name());
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
        if (absent != null) {
            throw new SchemaValidationException(where + " names '" + name + "', and " + absent + ", so the name "
                    + "reaches no value");
        }
        Object delivered = value;
        cell.ifPresent(c -> c.deliver(delivered));
    }

    /**
     * A slot whose {@code of} is a constructor holds a type's name, one segment, and the name is a member or refused
     * ({@link KindMembers}): the referent's value is of the slot's {@code of}. A value entry is refused as a value,
     * however many segments name it. A type reached through a namespace of types is not yet a spelling this library
     * reads.
     */
    private void judgeMember(String where, String name, List<String> segments, TsonSchema.SlotType slot,
                             Top referent, String entry) {
        String wants = where + " wants the name of a type whose value is of '" + slot.of() + "' (a member of '"
                + slot.name() + "'), and ";
        if (segments.size() > 1 && referent instanceof ValueEntry value) {
            throw new SchemaValidationException(wants + "'" + name + "' reaches into " + entry + ", which is "
                    + aValueNoType(value) + " -- and what a further segment selects in a value is a value too");
        }
        if (segments.size() > 1) {
            throw new UnsupportedOperationException(where + " names '" + name + "' at a position typed by the kind '"
                    + slot.name() + "', which holds a type's name -- this library does not yet read a type through "
                    + "a namespace of types, so the name is one segment");
        }
        if (KindMembers.isMember(name, slot.of(), merged::get,
                named -> TsonSchemaLinker.constructorOf(named, merged.get(named), facts),
                merged::get)) {
            return;
        }
        if (referent instanceof ValueEntry value) {
            throw new SchemaValidationException(wants + entry + " is " + aValueNoType(value));
        }
        String terminal = AliasChain.terminal(name, merged);
        throw new SchemaValidationException(wants + entry + " is an entry whose body is built by '"
                + TsonSchemaLinker.constructorOf(terminal, referent, facts).orElse("nothing") + "', which is no '"
                + slot.of() + "'");
    }

    /** What a value entry is, where a type is wanted: a value, which nothing can be typed by (§4.1). */
    static String aValueNoType(ValueEntry value) {
        return "a value of the type '" + value.type() + "' -- a value and not a type, so nothing can be typed by it";
    }

    /**
     * The type {@code definition} is a value of: a value entry's type, and otherwise the constructor that built the
     * body, its tag where that names a constructor and the kernel constructor the body's class mirrors where not.
     */
    private static Typed constructorOf(String tag, Top definition, Space space) {
        if (definition instanceof ValueEntry value) {
            return new Typed(space, value.type());
        }
        Typed source = new Typed(space, tag);
        Top constructor = source.definition();
        if (constructor != null && space.isConstructor(tag, constructor)) {
            return source;
        }
        return new Typed(space, TsonCompiledMetaSchema.typenameOf(definition));
    }

    /**
     * The value {@code host}, a record value the resolver's read bound, holds at its field {@code field}, or {@code
     * null} where the field is not present in it; {@code naming} says where, as a message opens. Read through the
     * descriptor that bound it ({@link DataBindContext#getDescriptor(Class)} of the host's class, in the context the
     * read bound it under), by the field's wire name: the component the binding wrote the field to, whatever the
     * component is called, and the value inside an {@code Optional} component. Only a FIXED field may have no
     * component (the bind agreement {@code RecordBindReader} enforces), and its value is the schema's, which the host
     * does not hold.
     */
    private Object fieldOf(Object host, String field, String naming) {
        if (values == null) {
            throw new IllegalStateException(naming + " is a field of a record value, and this schema was linked with no "
                    + "bind context to read it through -- link it with the context its values were bound under "
                    + "(TsonCompiledMetaRegistry.link)");
        }
        DataClass bound;
        try {
            bound = values.getDescriptor(host.getClass());
        } catch (DataBindException e) {
            throw new IllegalStateException(naming + " is a field of a record value bound as "
                    + host.getClass().getName() + ", which the bind context does not describe", e);
        }
        if (!(bound instanceof DataClassRecord record)) {
            throw new IllegalStateException(naming + " is a field of a record value, and the value is bound as "
                    + host.getClass().getName() + ", which is no record");
        }
        Integer index = record.fieldIndex().get(field);
        if (index == null) {
            throw new UnsupportedOperationException(naming + " is a FIXED field, which " + host.getClass().getName()
                    + " holds no component for -- its value is the schema's, and this library does not yet name a "
                    + "value through one");
        }
        DataClassField component = record.fields()[index];
        return component.isPresent(host) ? component.get(host) : null;
    }

    /**
     * The map a value of a namespace type holds: a {@link Namespace}'s entries, or the map the read built for a value
     * of a namespace type held under a key of another or in a record value's field, as the host's component holds it.
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

    /** A tree's value at a key or field, {@code null} where the tree holds none. */
    private static Object present(TsonValue value) {
        return value.isMissing() ? null : value;
    }

    /** The value under the key spelled {@code segment} of a namespace value, bound or held as a tree. */
    private static Optional<Object> keyed(Object value, String segment, String reached) {
        if (value instanceof TsonValue tree) {
            return Optional.ofNullable(present(tree.get(segment)));
        }
        return keyed(asMap(value, reached), segment);
    }

    /** The keys a namespace value holds, as a message lists them. */
    private static String keysOf(Object value, String reached) {
        if (value instanceof TsonMap tree) {
            return tree.entries().isEmpty() ? "none" : String.join(", ", tree.entries().stream()
                    .map(entry -> entry.key().asString().orElse(String.valueOf(entry.key()))).toList());
        }
        return keysOf(asMap(value, reached));
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
