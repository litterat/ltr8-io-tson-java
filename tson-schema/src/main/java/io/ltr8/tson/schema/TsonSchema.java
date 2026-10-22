package io.ltr8.tson.schema;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A resolved schema (Part 2 §8): the kernel's own {@code schema} type, <code>{type_name =&gt; top}</code>, plus
 * the governing-chain directives its own document header carried ({@code !!id}/{@code !!meta}/{@code
 * !!import}*, §2.2) -- the "produced schema" this module exists for, as opposed to {@code tson-compiler}'s
 * grammar-only {@code SchemaDocument}/{@code SchemaMap}. {@code entries}' insertion order is preserved, matching
 * {@code SchemaMap.declarations}' own ordering guarantee.
 *
 * <p><b>An entry is its value</b> (SPEC-FEEDBACK.md #14): a name maps to the body it resolves to -- a {@code
 * RecordBody}, an atom family's instance, a {@code Reference}, a {@code TemplateBody}, a {@code Data} -- and a
 * type body, a record template's included, carries its own IS-A chain ({@code Type.chain}). The entry's kind is
 * its value's ({@code TypeKind.of}), judged by origin ({@link TsonLinkedSchema#kindOf}).
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
 * itself produced (Part 2 §1.5's "one deliberate circularity in the series": meta-kernel's own
 * {@code !!meta} names its own {@code !!id}, and nothing else is allowed to). A real, stored flag,
 * not derived from {@code id().equals(meta())} -- a *derived* check can't tell "this schema really
 * was produced by reading meta-kernel.tn through the real two-pass bootstrap reader" apart from
 * "this schema merely happens to have matching {@code id}/{@code meta} fields," and the whole point
 * of gating {@link TsonSchemaRegistry#register}/{@code TsonSchemaLinker.linkBootstrap} on it is to keep
 * proving, continuously, that the real reader is what produces whatever ever gets registered under
 * meta-kernel's own identity -- not just that something of the right shape did. This one stays a flag
 * rather than becoming a type, unlike linked-ness above: it's true for exactly one
 * object in the entire system, ever, so a dedicated type would only relocate the flag (it would
 * still need to survive linking, so {@link TsonLinkedSchema} would need to carry it too) without
 * buying any safety a universal property like linked-ness actually needs.
 *
 * <p><b>{@link #valuesByName()}</b> -- for each entry whose body is a {@code data} constructor's instance, the
 * references that body holds by name (SPEC-FEEDBACK.md #13), each with the slot it fills, as the resolver's read
 * collected them: the reader records what it produced at a slot holding a value by name, so the list depends on
 * nothing the host class declares. The linker resolves each and judges it against the slot's declared type. A fact
 * the resolver hands the linker, never part of §8's output: no resolved document writes it.
 */
public record TsonSchema(String id, String meta, List<String> imports, AnnotatedMap<String, Top> entries,
                          boolean bootstrap, Map<String, List<ValueByName>> valuesByName,
                          Map<String, EntryFacts> facts) {

    /**
     * An entry's facts beyond its value ({@link TsonSchema}'s own Javadoc). {@code position} is where the
     * declaration's name sits in the schema text it was resolved from, absent for an entry the resolver minted
     * (which is what tells a derived name from a declared one). {@code annotations} are those written after
     * {@code =>}, on the value (§6): the value's own Java shape has no slot for them, and the ones written before
     * the name are the key's ({@link #entries}' {@link AnnotatedMap}). {@code constructor} is the constructor
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
     * One reference a {@code data} body holds by name: {@code slot} is the path from the body to the position it
     * fills -- a field name at a record, a key at a map, an index at an array, as the read descended -- and
     * {@code reference} the {@code type_ref} value read there, its {@code name} the qualified name as written.
     */
    public record ValueByName(List<String> slot, TypeRef reference) {

        public ValueByName {
            slot = List.copyOf(slot);
            Objects.requireNonNull(reference, "reference");
        }
    }

    public TsonSchema {
        Objects.requireNonNull(id, "!!id is required");
        Objects.requireNonNull(meta, "!!meta is required");
        imports = List.copyOf(imports);
        // AnnotatedMap is fillable while a binder or resolver builds one, so copy to an immutable form --
        // entries() has always been unmodifiable and that is part of a registered schema being locked.
        entries = AnnotatedMap.copyOf(entries);
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
    public TsonSchema(String id, String meta, List<String> imports, AnnotatedMap<String, Top> entries,
                       boolean bootstrap) {
        this(id, meta, imports, entries, bootstrap, Map.of(), Map.of());
    }

    /**
     * {@code entries} as a plain map -- the ordinary case, where no declaration name carries annotations.
     * §6 binds an annotation written before a declaration's name to that name, which in resolved form is
     * this map's key, so {@link AnnotatedMap} is what holds them; a caller with none to carry need not know.
     */
    public TsonSchema(String id, String meta, List<String> imports, Map<String, Top> entries,
                       boolean bootstrap) {
        this(id, meta, imports, AnnotatedMap.of(entries), bootstrap);
    }

    /** {@code bootstrap} defaults to {@code false} -- the common case: an ordinary, non-bootstrap schema. */
    public TsonSchema(String id, String meta, List<String> imports, Map<String, Top> entries) {
        this(id, meta, imports, entries, false);
    }

    /** This schema with {@code name}'s own annotations recorded -- everything else unchanged. */
    public TsonSchema withNameAnnotations(String name, Annotations annotations) {
        return new TsonSchema(id, meta, imports, entries.withAnnotations(name, annotations), bootstrap, valuesByName,
                facts);
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
     * carries, which the resolver writes wherever an entry closes one -- a record template's member, a DATA
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
     * layer's own tightening of either are one class), which is why an entry's own facts are asked first.
     */
    public static String constructorOf(Top value) {
        Typename typename = value.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(value.getClass() + " has no @Typename -- every Top leaf carries one, "
                    + "naming the constructor it is the body of");
        }
        return typename.name();
    }
}
