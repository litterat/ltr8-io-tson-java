package io.ltr8.tson.schema.meta;

import io.ltr8.tson.base.SourcePosition;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The meta-kernel's {@code type_definition} record, resolved ([TSON-SCHEMA] §4, §8.1) -- what every schema
 * declaration ultimately resolves to. Four components are the kernel's fields: {@code source},
 * {@code supertypes}, {@code subtypes} and {@code body}. {@code source} is OPTIONAL and omitted from written
 * output when absent, as any {@code Optional} component is. {@code supertypes}/{@code subtypes} are OPTIONAL
 * in the kernel too ({@code [type_name]?}) but are modelled as a bare, always-present {@code List}: absent
 * and empty are one list here, which the compact constructor normalises, and an empty one writes as
 * {@code []}. {@code annotations} is the wire-annotation carrier for the declaration's own annotations.
 *
 * <p>{@code kind} and {@code position} are {@code @Unbound}: §8.1's {@code type_definition} declares neither
 * field, so no schema fills them and the strict binding check would otherwise call each a mismatch. A kind
 * is derived from an entry's own supertypes and body (§4.1, §8.1) and is computed at resolution for this
 * implementation's own use, never written; {@code position} is kept for diagnostics.
 *
 * <p>{@code position} -- where this declaration sits in whatever schema source text it was resolved
 * from, when known -- is deliberately excluded from {@link #equals}/{@link #hashCode} (both hand-written
 * below, not generated), which compare {@code source}, {@code kind}, {@code supertypes}, {@code subtypes}
 * and {@code body} and leave {@code annotations} out as well. Those five are compared structurally
 * throughout the resolver tests (a hand-built expected {@code TypeDefinition} against a real resolved one);
 * if {@code position} participated
 * in equality, two {@code TypeDefinition}s representing the same logical type from different parses (or the
 * same source parsed twice) would not compare equal. {@code toString()} stays generated -- {@code position}
 * carries no reference back to this type or its own schema, so there is no cycle risk in printing it. The
 * compact constructor carries {@code @Record} because the convenience constructors beside it would
 * otherwise leave {@code tson-bind}'s constructor selection ambiguous (see {@link IntegerSize}).
 */
public record TypeDefinition(Optional<TypeRef> source, @Unbound TypeKind kind,
                              List<String> supertypes, List<String> subtypes,
                              Top body, @Unbound Optional<SourcePosition> position,
                              Annotations annotations) {

    @Record
    public TypeDefinition {
        // Absent and empty are the same list here. [TSON-SCHEMA] declares both OPTIONAL with no default
        // ([type_name]?), so a definition bound from a resolved-form document that omits one arrives with
        // null where one resolved from source arrives with an empty list.
        supertypes = supertypes == null ? List.of() : List.copyOf(supertypes);
        subtypes = subtypes == null ? List.of() : List.copyOf(subtypes);
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public TypeDefinition(Optional<TypeRef> source, TypeKind kind,
                           List<String> supertypes, List<String> subtypes, Top body,
                           Optional<SourcePosition> position) {
        this(source, kind, supertypes, subtypes, body, position, Annotations.empty());
    }

    /** Same as the canonical constructor, {@code position} absent -- for a caller that does not know its source position. */
    public TypeDefinition(Optional<TypeRef> source, TypeKind kind,
                           List<String> supertypes, List<String> subtypes, Top body) {
        this(source, kind, supertypes, subtypes, body, Optional.empty());
    }

    /**
     * Whether this entry's variants occupy distinct discrimination classes -- present on a choice and absent
     * on everything else ([TSON-SCHEMA] §5.4).
     *
     * <p><b>Derived, not stored.</b> The fact is about a choice's variant list, so it lives on the body that
     * holds one: an entry with no variants has nowhere to put it, and cannot claim a disjointness it has no
     * variants to be disjoint over. Like {@code subtypes} it is a cache -- fully recomputable, never trusted,
     * discarded and recomputed on ingest.
     */
    public Optional<Boolean> disjoint() {
        return body instanceof ChoiceBody choice ? choice.disjoint() : Optional.empty();
    }

    /**
     * The type parameters this entry declares -- {@code []} unless its body is held ([TSON-SCHEMA] §5.10).
     *
     * <p><b>Derived, not stored.</b> A held body carries the list it binds, so "does this entry declare
     * parameters?" and "what does its body hold?" are one question with one answer and cannot disagree.
     * §5.10's "Closed entries are parameter-free" is a MUST over resolver output; here it is structural, and
     * a closed entry has nowhere to put a parameter list at all.
     */
    public List<String> parameters() {
        return body instanceof TemplateBody held ? held.parameters() : List.of();
    }

/** A fresh PRODUCT definition with no source, supertypes or parameters -- {@code integer_size}'s own shape. */
    public static TypeDefinition product(Top body) {
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), List.of(), body);
    }

    /**
     * A reference definition whose target is a bare name -- {@code type_name}, {@code annotation},
     * {@code doc}, and similar kernel aliases.
     */
    public static TypeDefinition reference(String target) {
        return reference(TypeRef.of(target));
    }

    /**
     * A reference definition whose target may itself carry arguments -- an application of a
     * non-constructor template like {@code box<text>} (§5.10). {@code target} is reused as both {@code
     * source} and {@code body.target}, and points at the application as written; materialisation
     * replaces it with the instantiation entry that closing it produces. An application of a real
     * <em>constructor</em> never reaches here -- the desugar phase rewrites it into a construction well
     * before resolution.
     */
    public static TypeDefinition reference(TypeRef target) {
        return new TypeDefinition(Optional.of(target), TypeKind.REFERENCE, List.of(),
                List.of(), new Reference(target));
    }

    /** A copy of this definition with {@code body} replaced -- every other component unchanged. */
    public TypeDefinition withBody(Top body) {
        return new TypeDefinition(source, kind, supertypes, subtypes, body,
                position, annotations);
    }

    /** A copy of this definition with {@code position} replaced -- every other component unchanged. */
    public TypeDefinition withPosition(Optional<SourcePosition> position) {
        return new TypeDefinition(source, kind, supertypes, subtypes, body,
                position, annotations);
    }

    /** A copy of this definition with {@code annotations} replaced -- every other component unchanged. */
    public TypeDefinition withAnnotations(Annotations annotations) {
        return new TypeDefinition(source, kind, supertypes, subtypes, body,
                position, annotations);
    }

    /** Excludes {@code position} -- see this class's own Javadoc for why. */
    @Override
    public boolean equals(Object o) {
        return o instanceof TypeDefinition other
                && Objects.equals(source, other.source)
                && kind == other.kind
                && Objects.equals(supertypes, other.supertypes)
                && Objects.equals(subtypes, other.subtypes)
                && Objects.equals(body, other.body);
    }

    /** Excludes {@code position} -- see this class's own Javadoc for why. */
    @Override
    public int hashCode() {
        return Objects.hash(source, kind, supertypes, subtypes, body);
    }
}
