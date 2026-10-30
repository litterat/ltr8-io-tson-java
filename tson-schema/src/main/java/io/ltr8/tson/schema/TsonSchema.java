package io.ltr8.tson.schema;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A resolved schema (Part 2 §8): a value of the kernel's {@code schema}, the namespace type
 * <code>{type_name =&gt; declared}</code> ({@link #entries}, a {@link Namespace} typed {@value #SCHEMA}), plus the
 * directives its own document header carried ({@code !!id}, {@code !!import}*, §2.2)
 * -- the "produced schema" this module exists for, as opposed to {@code tson-compiler}'s grammar-only {@code
 * SchemaDocument}/{@code SchemaMap}. {@code entries}' insertion order is preserved, matching {@code
 * SchemaMap.declarations}' own ordering guarantee.
 *
 * <p><b>An entry is its value</b> ([TSON-SCHEMA] §8.1): a name maps to the body it resolves to -- a {@code
 * RecordBody}, an atom family's instance, a {@code Reference}, a {@code TemplateBody}, a {@code ValueEntry} -- and a
 * type body, a record template's included, carries its own IS-A chain ({@code Type.chain}). The entry's kind is
 * its value's ({@code EntryKind.of}), judged by origin ({@link TsonLinkedSchema#kindOf}).
 *
 * <p><b>{@link #facts} holds what a value's Java shape cannot</b>, per entry ({@link EntryFacts}): where it was
 * declared, the annotations written on its value, the constructor its value applies and the entry a refinement
 * names. None of it is a field of any constructor, so binding a value never fills it, and none of it is written.
 *
 * <p><b>An instantiation's application is a key annotation</b>, {@code @application} ({@link #applicationOf}):
 * an entry of any kind can close a template application, so the fact is about the entry and sits beside it, as
 * {@code @synthetic} does, where resolved output writes it and ingest reads it as stated.
 *
 * <p><b>{@code id} is required, not {@code Optional}</b> -- the grammar itself marks {@code !!id} optional (a raw
 * parsed {@code SchemaDocument} can genuinely lack one), but a {@code TsonSchema} value -- the *resolved*,
 * actually-usable thing this module represents -- always needs a real identity. A document with no {@code !!id}
 * simply never becomes a {@code TsonSchema} at all -- {@code TsonSchemaResolver}'s own document-level resolution
 * enforces this before ever constructing one (see its own Javadoc on {@code resolveSchema}'s {@code !!id}
 * validation).
 *
 * <p><b>A record</b>, genuinely immutable: the compact constructor defensively copies every collection, and
 * convenience constructors cover the common case (fresh, non-bootstrap, nothing held by name) without every caller
 * spelling out the trailing components.
 *
 * <p><b>No {@code materialised} flag</b> -- "has this schema been
 * through linking" is a *type* distinction, not a runtime-checked boolean: {@code
 * TsonSchemaLinker.link} is the only thing that produces a {@link TsonLinkedSchema}, and {@link
 * TsonSchemaRegistry#register} only accepts one, so "you can't register something that hasn't been
 * linked" is enforced at compile time, not by a flag every caller has to remember to check. See
 * {@link TsonLinkedSchema}'s own Javadoc for why it's a deliberately separate, unrelated record
 * with the identical shape, not a subtype (records are implicitly final -- couldn't be one anyway)
 * and not interchangeable with this class, even though {@link TsonSchemaRegistry}'s own storage stays
 * plain {@code TsonSchema} (a {@code TsonLinkedSchema} is unwrapped back into one the moment it's
 * actually stored -- see {@link TsonSchemaRegistry#register}'s own Javadoc for why linked-ness stops
 * mattering once a schema is safely registered).
 *
 * <p><b>{@link #bootstrap()}</b> -- {@code true} <i>only</i> for a schema {@code MetaKernelBootstrapResolver}
 * itself produced (the kernel, pre-loaded by special resolution, [TSON-SCHEMA] §3.4). A real, stored flag,
 * not derived from the identity -- a *derived* check can't tell "this schema really
 * was produced by reading meta-kernel.tn through the real bootstrap reader" apart from
 * "this schema merely happens to carry the kernel's {@code !!id}," and the whole point
 * of gating {@link TsonSchemaRegistry#register}/{@code TsonSchemaLinker.linkBootstrap} on it is to keep
 * proving, continuously, that the real reader is what produces whatever ever gets registered under
 * meta-kernel's own identity -- not just that something of the right shape did. This one stays a flag
 * rather than becoming a type, unlike linked-ness above: it's true for exactly one
 * object in the entire system, ever, so a dedicated type would only relocate the flag (it would
 * still need to survive linking, so {@link TsonLinkedSchema} would need to carry it too) without
 * buying any safety a universal property like linked-ness actually needs.
 *
 * <p><b>{@link #valuesByName()}</b> -- for each value entry ({@code ValueEntry}), the references that value holds
 * by name ([TSON-SCHEMA] §5.12), each with the
 * slot it fills and that slot's declared type, as the resolver's read collected them: the reader at a kind-, reference-
 * or {@code type_ref}-typed slot records the name it read there whatever the host class holds, so the list depends on
 * nothing the host declares. The linker resolves each and judges it against the slot's declared type. A fact the
 * resolver hands the linker, never part of §8's output: no resolved document writes it.
 */
public record TsonSchema(String id, List<String> imports, Namespace<Top> entries,
                          boolean bootstrap, Map<String, List<ValueByName>> valuesByName,
                          Map<String, EntryFacts> facts) {

    /**
     * An entry's facts beyond its value ({@link TsonSchema}'s own Javadoc). {@code position} is where the
     * declaration's name sits in the schema text it was resolved from, absent for an entry the resolver minted
     * (which is what tells a derived name from a declared one). {@code annotations} are those written after
     * {@code =>}, on the value (§6): the value's own Java shape has no slot for them, and the ones written before
     * the name are the key's ({@link #entries}). {@code constructor} is the constructor
     * the value applies -- the tag resolved output writes, {@code !integer_type}, {@code !enum}, {@code
     * !set_type} -- which binding erases into a class that several constructors share. {@code refines} is the
     * entry a {@code ^} declaration's head names, as written: §5.7's refinement restates fields and adds none,
     * which is what lets a tightened constructor bind as the one it tightens, and the chain cannot tell it from a
     * composition adding nothing. All of it is provenance, and none of it is written ([TSON-SCHEMA] §8.1).
     * {@code synthetic} is the key's {@code @synthetic} mark as {@link #factsOf} reads it: a form no author
     * named, which a message names by its spelling or not at all.
     */
    public record EntryFacts(Optional<SourcePosition> position, Annotations annotations,
                             Optional<String> constructor, Optional<String> refines, boolean synthetic) {

        /** An entry with none of the five. */
        public static final EntryFacts NONE = new EntryFacts(Optional.empty(), Annotations.empty(), Optional.empty(),
                Optional.empty(), false);

        public EntryFacts {
            position = position == null ? Optional.empty() : position;
            annotations = annotations == null ? Annotations.empty() : annotations;
            constructor = constructor == null ? Optional.empty() : constructor;
            refines = refines == null ? Optional.empty() : refines;
        }

        /** Whether this holds nothing at all -- the facts a schema need not record. */
        public boolean isEmpty() {
            return position.isEmpty() && annotations.isEmpty() && constructor.isEmpty() && refines.isEmpty()
                    && !synthetic;
        }

        public EntryFacts withPosition(Optional<SourcePosition> position) {
            return new EntryFacts(position, annotations, constructor, refines, synthetic);
        }

        public EntryFacts withAnnotations(Annotations annotations) {
            return new EntryFacts(position, annotations, constructor, refines, synthetic);
        }

        public EntryFacts withConstructor(Optional<String> constructor) {
            return new EntryFacts(position, annotations, constructor, refines, synthetic);
        }

        public EntryFacts withRefines(Optional<String> refines) {
            return new EntryFacts(position, annotations, constructor, refines, synthetic);
        }

        public EntryFacts withSynthetic(boolean synthetic) {
            return new EntryFacts(position, annotations, constructor, refines, synthetic);
        }

        /** An entry declared at {@code position}. */
        public static EntryFacts at(Optional<SourcePosition> position) {
            return NONE.withPosition(position);
        }

        /** An entry whose value applies {@code constructor}. */
        public static EntryFacts constructedBy(String constructor) {
            return NONE.withConstructor(Optional.of(constructor));
        }
    }

    /**
     * One reference a value body holds by name: {@code slot} is the path from the body to the position it
     * fills -- a field name at a record, a key at a map, an index at an array, as the read descended --, {@code
     * type} that position's declared type, as the reader that read the name knows it, and {@code reference} the
     * {@code type_ref} value read there, its {@code name} the qualified name as written; {@code cell} is the host's
     * {@code Ref} where the host holds one.
     */
    public record ValueByName(List<String> slot, SlotType type, TypeRef reference,
                              Optional<io.ltr8.tson.schema.meta.Ref<?>> cell) {

        public ValueByName {
            slot = List.copyOf(slot);
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * The declared type of a slot holding a reference by name, in the namespace of the schema declaring the slot's
     * record, which the reading schema's closure contains: what the reader of that slot was built from, so the linker
     * judges the name without walking the path back through the declarations. {@code of} is a kind's {@code of} (a
     * constructor), a reference's {@code T} (a type), and {@code type_ref} itself for the application form; {@code
     * name} is the declared type as a message names it, {@code kind<record>} or {@code type_kind}.
     */
    public record SlotType(Role role, String of, String name) {

        /** A {@code type_ref}-typed slot, which holds the application form. */
        public static final SlotType APPLICATION = new SlotType(Role.APPLICATION, "type_ref", "type_ref");

        /** What the slot holds, which decides how the name it holds is judged. */
        public enum Role {
            /** A type's name, a member of the kind ({@code kind<record>}, {@code type_kind}). */
            KIND,
            /** A value's qualified name, IS-A the reference's {@code T} ({@code ref<method>}). */
            REFERENCE,
            /** A name or application, judged by resolution and arity ({@code type_ref}). */
            APPLICATION
        }

        public SlotType {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(of, "of");
            Objects.requireNonNull(name, "name");
        }

        /** A slot typed by a kind whose {@code of} is {@code of}, named {@code name} where an author named it. */
        public static SlotType kind(String of, String name) {
            return new SlotType(Role.KIND, of, name != null ? name : "kind<" + of + ">");
        }

        /** A slot typed {@code ref<of>}, named {@code name} where an author named it. */
        public static SlotType reference(String of, String name) {
            return new SlotType(Role.REFERENCE, of, name != null ? name : "ref<" + of + ">");
        }
    }

    public TsonSchema {
        Objects.requireNonNull(id, "!!id is required");
        imports = List.copyOf(imports);
        // A Namespace's map is an immutable copy, which is part of a registered schema being locked.
        if (!SCHEMA.equals(entries.type())) {
            throw new IllegalArgumentException("a schema's entries are a value of '" + SCHEMA + "', not of '"
                    + entries.type() + "'");
        }
        Map<String, List<ValueByName>> held = new LinkedHashMap<>();
        valuesByName.forEach((name, values) -> held.put(name, List.copyOf(values)));
        valuesByName = Collections.unmodifiableMap(held);
        Map<String, EntryFacts> kept = new LinkedHashMap<>();
        facts.forEach((name, fact) -> {
            if (!fact.isEmpty()) {
                kept.put(name, fact);
            }
        });
        facts = Collections.unmodifiableMap(kept);
    }

    /**
     * No value held by name and no facts -- a schema assembled by hand, whose entries were declared nowhere a
     * position could name.
     */
    public TsonSchema(String id, List<String> imports, AnnotatedMap<String, Top> entries,
                       boolean bootstrap) {
        this(id, imports, entries, bootstrap, Map.of(), Map.of());
    }

    /** {@code entries}, a map with each key's annotations, as the schema map: a value of {@value #SCHEMA}. */
    public TsonSchema(String id, List<String> imports, AnnotatedMap<String, Top> entries,
                       boolean bootstrap, Map<String, List<ValueByName>> valuesByName,
                       Map<String, EntryFacts> facts) {
        this(id, imports, new Namespace<>(SCHEMA, entries), bootstrap, valuesByName, facts);
    }

    /**
     * {@code entries} as a plain map -- the ordinary case, where no declaration name carries annotations.
     * §6 binds an annotation written before a declaration's name to that name, which in resolved form is
     * the schema map's key, so the {@link Namespace}'s {@link AnnotatedMap} is what holds them; a caller with none
     * to carry need not know.
     */
    public TsonSchema(String id, List<String> imports, Map<String, Top> entries,
                       boolean bootstrap) {
        this(id, imports, AnnotatedMap.of(entries), bootstrap);
    }

    /** {@code bootstrap} defaults to {@code false} -- the common case: an ordinary, non-bootstrap schema. */
    public TsonSchema(String id, List<String> imports, Map<String, Top> entries) {
        this(id, imports, entries, false);
    }

    /** This schema with {@code name}'s own annotations recorded -- everything else unchanged. */
    public TsonSchema withNameAnnotations(String name, Annotations annotations) {
        return new TsonSchema(id, imports, entries.withAnnotations(name, annotations), bootstrap, valuesByName,
                facts);
    }

    /**
     * The schemas whose namespaces this schema's own namespace takes in: its {@code !!import}s in header order, a
     * target named twice (in two spellings of one identity) listed once. One namespace per schema is its own entries
     * and every entry of these, transitively ([TSON-SCHEMA] §2.2.3). A schema naming itself is listed, and refused as
     * the import cycle it is.
     */
    public List<String> namespaceSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String imported : imports) {
            sources.putIfAbsent(CanonicalIdentity.canonicalize(imported), imported);
        }
        return List.copyOf(sources.values());
    }

    /** {@code name}'s facts, {@link EntryFacts#NONE} for an entry with none or a name this schema lacks. */
    public EntryFacts factsOf(String name) {
        EntryFacts known = facts.getOrDefault(name, EntryFacts.NONE);
        return entries.getAnnotations(name).has(SYNTHETIC) ? known.withSynthetic(true) : known;
    }

    /** Where {@code name} was declared -- absent for an entry the resolver minted. */
    public Optional<SourcePosition> positionOf(String name) {
        return factsOf(name).position();
    }

    /**
     * The application {@code name} is the instantiation of ([TSON-SCHEMA] §8.2): the {@code @application} its key
     * carries, which the resolver writes wherever an entry closes one -- a record template's member, a namespace
     * entry, the alias a template's application closes to. Empty for an entry that closes none.
     */
    public Optional<TypeRef> applicationOf(String name) {
        return applicationOf(entries.getAnnotations(name));
    }

    /** The application a key's {@code annotations} name, if they carry {@code @application}. */
    public static Optional<TypeRef> applicationOf(Annotations annotations) {
        return annotations.value(APPLICATION, TypeRef.class);
    }

    /** {@code annotations} with {@code @application:application} added -- the key of an instantiation. */
    public static Annotations withApplication(Annotations annotations, TypeRef application) {
        List<io.ltr8.annotation.Annotation> values = new java.util.ArrayList<>(annotations.values());
        values.removeIf(annotation -> annotation.name().equals(APPLICATION));
        values.add(io.ltr8.annotation.Annotation.of(APPLICATION, application));
        return Annotations.of(values);
    }

    /** The kernel's {@code schema}, the namespace type the schema map is a value of. */
    public static final String SCHEMA = "schema";

    /** The kernel's {@code application} annotation, derived and never author-written (§8.2). */
    public static final String APPLICATION = "application";

    /** The kernel's {@code synthetic} annotation: the key of a form no author named (§8.2). */
    public static final String SYNTHETIC = "synthetic";

    /**
     * The constructor {@code name}'s value applies: the one its facts record, and otherwise the one its Java
     * class is the body of ({@link #constructorOf(Top)}).
     */
    public Optional<String> constructorOf(String name) {
        Top value = entries.get(name);
        return factsOf(name).constructor()
                .or(() -> value == null ? Optional.empty() : Optional.of(constructorOf(value)));
    }

    /**
     * The constructor {@code value}'s class is named for, by its {@link Typename} -- exact where one constructor
     * has that shape, and only one of them where several share it ({@code enum_type}, {@code enum} and a meta
     * layer's own tightening of either are one class), which is why an entry's own facts are asked first. A
     * value entry names its own: the type it is a value of.
     */
    public static String constructorOf(Top value) {
        if (value instanceof io.ltr8.tson.schema.meta.ValueEntry entry) {
            return entry.type();
        }
        Typename typename = value.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(value.getClass() + " has no @Typename -- every Top leaf carries one, "
                    + "naming the constructor it is the body of");
        }
        return typename.name();
    }
}
