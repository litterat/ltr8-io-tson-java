package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A resolved schema (Part 2 §8): the kernel's own {@code schema} type, {@code map<type_name,
 * type_definition>} (§9), plus the governing-chain directives its own document header carried
 * ({@code !!id}/{@code !!meta}/{@code !!import}*, §2.2) -- the "produced schema" this module
 * exists for, as opposed to {@code tson-compiler}'s grammar-only {@code SchemaDocument}/{@code
 * SchemaMap}. {@code entries}' insertion order is preserved, matching {@code SchemaMap.
 * declarations}' own ordering guarantee.
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
public record TsonSchema(String id, String meta, List<String> imports, AnnotatedMap<String, TypeDefinition> entries,
                          boolean bootstrap, Map<String, List<ValueByName>> valuesByName) {

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
    }

    /** No value held by name -- every schema whose entries apply no {@code data} constructor that holds one. */
    public TsonSchema(String id, String meta, List<String> imports, AnnotatedMap<String, TypeDefinition> entries,
                       boolean bootstrap) {
        this(id, meta, imports, entries, bootstrap, Map.of());
    }

    /**
     * {@code entries} as a plain map -- the ordinary case, where no declaration name carries annotations.
     * §6 binds an annotation written before a declaration's name to that name, which in resolved form is
     * this map's key, so {@link AnnotatedMap} is what holds them; a caller with none to carry need not know.
     */
    public TsonSchema(String id, String meta, List<String> imports, Map<String, TypeDefinition> entries,
                       boolean bootstrap) {
        this(id, meta, imports, AnnotatedMap.of(entries), bootstrap);
    }

    /** {@code bootstrap} defaults to {@code false} -- the common case: an ordinary, non-bootstrap schema. */
    public TsonSchema(String id, String meta, List<String> imports, Map<String, TypeDefinition> entries) {
        this(id, meta, imports, entries, false);
    }

    /** This schema with {@code name}'s own annotations recorded -- everything else unchanged. */
    public TsonSchema withNameAnnotations(String name, Annotations annotations) {
        return new TsonSchema(id, meta, imports, entries.withAnnotations(name, annotations), bootstrap, valuesByName);
    }
}
