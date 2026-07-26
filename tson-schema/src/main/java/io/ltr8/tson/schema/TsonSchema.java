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
 * this module represents -- always needs a real identity: {@link SchemaRegistry#register} already
 * required it (throwing if absent) before this change, and every other consumer (a self-referential
 * check, an {@code !!import} target lookup, a {@code TsonCompiledRegistry} lookup key, ...) needs
 * one to key off of. A document with no {@code !!id} simply never becomes a {@code TsonSchema} at
 * all -- {@code SchemaResolver}'s own document-level resolution enforces this before ever
 * constructing one (see its own Javadoc on {@code resolveAll}'s {@code !!id} validation).
 *
 * <p><b>A record, not a plain class</b> (2026-07-26, on the user's own explicit direction) --
 * previously kept as a plain class specifically so a bootstrap subclass ({@code MetaSchema}) could
 * {@code extend} it directly; now that {@code MetaSchema} is gone (see {@link #bootstrap()}'s own
 * Javadoc), nothing needs to subclass this anymore, so a record -- genuinely immutable, matching
 * {@code imports}/{@code entries}'s own already-defensive copying, and with accessor names that
 * were already following record-style bare-noun convention by hand -- is the better fit. The
 * compact constructor still defensively copies both collections; a second, convenience constructor
 * covers the common case (fresh, unmaterialized, non-bootstrap) without every caller spelling out
 * both trailing booleans.
 *
 * <p><b>{@link #materialised()} and {@link #bootstrap()}</b> (added 2026-07-26, replacing the
 * previous, content-free {@code MetaSchema extends TsonSchema} marker subtype):
 * <ul>
 *   <li>{@link #materialised()} -- {@code false} for anything fresh out of ordinary resolution
 *   ({@code SchemaResolver.resolveAll}, {@code MetaKernelParser}'s own bootstrap output, or any
 *   hand-built schema), {@code true} only for {@code SchemaValidator}'s own output (import merging,
 *   argument-bearing {@code type_ref} synthesis, and reference validation all done). A genuinely
 *   general property -- any consumer that needs materialization already done (e.g. a future {@code
 *   TsonSchemaParser.compile} precondition) can check it directly instead of trusting caller
 *   discipline/naming convention.</li>
 *   <li>{@link #bootstrap()} -- {@code true} <i>only</i> for a schema {@code MetaKernelParser}
 *   itself produced (Part 2 §1.5's "one deliberate circularity in the series": meta-kernel's own
 *   {@code !!meta} names its own {@code !!id}, and nothing else is allowed to). Deliberately a real,
 *   stored flag, not derived from {@code id().equals(meta())} the way an earlier version of this
 *   class had it (corrected on the user's own explicit direction) -- a *derived* check can't tell
 *   "this schema really was produced by reading meta-kernel.tn1 through the real two-pass bootstrap
 *   reader" apart from "this schema merely happens to have matching {@code id}/{@code meta}
 *   fields," and the whole point of gating {@link SchemaRegistry#register}/{@link
 *   SchemaRegistry#materializeBootstrap} on it is to keep proving, continuously, that the real
 *   reader is what produces whatever ever gets registered under meta-kernel's own identity -- not
 *   just that something of the right shape did. {@code SchemaValidator} carries this flag through
 *   materialization unchanged (materializing doesn't change *what produced* a schema, only whether
 *   it's been validated yet).</li>
 * </ul>
 */
public record TsonSchema(String id, String meta, List<String> imports, Map<String, TypeDefinition> entries,
                          boolean materialised, boolean bootstrap) {

    public TsonSchema {
        Objects.requireNonNull(id, "!!id is required");
        Objects.requireNonNull(meta, "!!meta is required");
        imports = List.copyOf(imports);
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** {@code materialised}/{@code bootstrap} default to {@code false} -- the common case: a freshly-resolved, not-yet-registered, ordinary schema. */
    public TsonSchema(String id, String meta, List<String> imports, Map<String, TypeDefinition> entries) {
        this(id, meta, imports, entries, false, false);
    }
}
