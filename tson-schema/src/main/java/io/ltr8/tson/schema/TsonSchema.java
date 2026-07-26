package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A resolved schema (Part 2 §8): the kernel's own {@code schema} type, {@code map<type_name,
 * type_definition>} (§9), plus the governing-chain directives its own document header carried
 * ({@code !!id}/{@code !!meta}/{@code !!import}*, §2.2) -- the "produced schema" this module
 * exists for, as opposed to {@code tson-parser}'s grammar-only {@code SchemaDocument}/{@code
 * SchemaMap}. {@code entries}' insertion order is preserved, matching {@code SchemaMap.
 * declarations}' own ordering guarantee.
 *
 * <p><b>{@code id} is required, not {@code Optional}</b> (2026-07-26, on the user's own explicit
 * direction) -- the grammar itself marks {@code !!id} optional (a raw parsed {@code SchemaDocument}
 * can genuinely lack one), but a {@code TsonSchema} value -- the *resolved*, actually-usable thing
 * this module represents -- always needs a real identity. A document with no {@code !!id} simply
 * never becomes a {@code TsonSchema} at all -- {@code SchemaResolver}'s own document-level
 * resolution enforces this before ever constructing one (see its own Javadoc on {@code
 * resolveAll}'s {@code !!id} validation).
 *
 * <p><b>A record, not a plain class</b> (2026-07-26, on the user's own explicit direction) --
 * previously kept as a plain class specifically so a bootstrap subclass ({@code MetaSchema}) could
 * {@code extend} it directly; now that {@code MetaSchema} is gone, nothing needs to subclass this
 * anymore, so a record -- genuinely immutable, matching {@code imports}/{@code entries}'s own
 * already-defensive copying, and with accessor names that were already following record-style
 * bare-noun convention by hand -- is the better fit. The compact constructor still defensively
 * copies both collections; a second, convenience constructor covers the common case (fresh,
 * non-bootstrap) without every caller spelling out the trailing boolean.
 *
 * <p><b>No {@code materialised} flag</b> (removed 2026-07-27, on the user's own explicit
 * direction, replacing an earlier version of this class that carried one) -- "has this schema been
 * through linking" is now a *type* distinction, not a runtime-checked boolean: {@link
 * SchemaLinker#link} is the only thing that produces a {@link TsonLinkedSchema}, and {@link
 * TsonSchemaRegistry#register} only accepts one, so "you can't register something that hasn't been
 * linked" is enforced at compile time, not by a flag every caller has to remember to check. See
 * {@link TsonLinkedSchema}'s own Javadoc for why it's a deliberately separate, unrelated record
 * with the identical shape, not a subtype (records are implicitly final -- couldn't be one anyway)
 * and not interchangeable with this class, even though {@link TsonSchemaRegistry}'s own storage stays
 * plain {@code TsonSchema} (a {@code TsonLinkedSchema} is unwrapped back into one the moment it's
 * actually stored -- see {@link TsonSchemaRegistry#register}'s own Javadoc for why linked-ness stops
 * mattering once a schema is safely registered).
 *
 * <p><b>{@link #bootstrap()}</b> -- {@code true} <i>only</i> for a schema {@code MetaKernelParser}
 * itself produced (Part 2 §1.5's "one deliberate circularity in the series": meta-kernel's own
 * {@code !!meta} names its own {@code !!id}, and nothing else is allowed to). A real, stored flag,
 * not derived from {@code id().equals(meta())} -- a *derived* check can't tell "this schema really
 * was produced by reading meta-kernel.tn1 through the real two-pass bootstrap reader" apart from
 * "this schema merely happens to have matching {@code id}/{@code meta} fields," and the whole point
 * of gating {@link TsonSchemaRegistry#register}/{@link TsonSchemaRegistry#linkBootstrap} on it is to keep
 * proving, continuously, that the real reader is what produces whatever ever gets registered under
 * meta-kernel's own identity -- not just that something of the right shape did. Unlike {@code
 * materialised}, this one stays a flag rather than becoming a type: it's true for exactly one
 * object in the entire system, ever, so a dedicated type would only relocate the flag (it would
 * still need to survive linking, so {@link TsonLinkedSchema} would need to carry it too) without
 * buying any safety a universal property like linked-ness actually needs.
 */
public record TsonSchema(String id, String meta, List<String> imports, Map<String, TypeDefinition> entries,
                          boolean bootstrap) {

    public TsonSchema {
        Objects.requireNonNull(id, "!!id is required");
        Objects.requireNonNull(meta, "!!meta is required");
        imports = List.copyOf(imports);
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** {@code bootstrap} defaults to {@code false} -- the common case: an ordinary, non-bootstrap schema. */
    public TsonSchema(String id, String meta, List<String> imports, Map<String, TypeDefinition> entries) {
        this(id, meta, imports, entries, false);
    }
}
