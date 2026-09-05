package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The meta-kernel's {@code type_definition} record, resolved (Part 2 §4, §8.1) -- what every
 * schema declaration ultimately resolves to. {@code kind} is REQUIRED with no default and always
 * appears in output; {@code source}/{@code disjoint} are genuinely OPTIONAL ({@code
 * Optional<TypeRef>}/{@code Optional<Boolean>}) and omitted from written output when absent, the
 * same as any other {@code Optional}-wrapped scalar/record field bound through plain {@code
 * TsonObjectWriter.toTson}. {@code supertypes}/{@code subtypes} are conceptually
 * OPTIONAL in the kernel too ({@code [type_name]?} etc.), but modeled here as a bare, always-present
 * {@code List} rather than {@code Optional<List<...>>} -- {@code tson-bind} doesn't support an
 * {@code Optional} wrapping a parameterized collection type yet, so there's no way to opt an empty
 * list into the same omit-when-absent treatment; it writes as {@code []} instead. Likewise {@code
 * constructor}, a bare {@code boolean}, always appears (as {@code false}) rather than being omitted
 * at its nominal default -- a hand-written writer could special-case "omit when at default" for
 * these; plain generic binding has no such concept beyond {@code Optional.empty()}/{@code null}.
 * See {@code TsonSchema}'s and {@code DefinitionResolverTest}'s own notes for what this means in
 * practice: written output is structurally faithful but more verbose than the non-normative
 * {@code meta-kernel-resolved.tn} fixture's own hand-authored, terser conventions.
 *
 * <p>{@code position} is {@code @Unbound}: §8.1's {@code type_definition} declares no such field, so no
 * schema fills it and the strict binding check would otherwise call it a mismatch. It is this
 * implementation's own, kept for diagnostics -- exactly the case the marker exists for.
 *
 * <p>{@code position} -- where this declaration sits in whatever schema source text it was resolved
 * from, when known -- is deliberately excluded from {@link #equals}/{@link #hashCode} (both
 * hand-written below, not generated). Every other component here is compared structurally
 * throughout this repo's own resolver test suite (a hand-built expected {@code TypeDefinition}
 * against a real resolved one); if {@code position} participated in equality, two {@code
 * TypeDefinition}s representing the same logical type from different parses (or the same source
 * parsed twice) would stop comparing equal, breaking that whole test style. {@code toString()} stays
 * generated -- {@code position} carries no reference back to this type or its own schema, so there's
 * no cycle risk in printing it. The compact constructor now carries {@code @Record} -- required as
 * soon as a second, convenience constructor exists, or {@code tson-bind}'s own constructor-selection
 * fails outright (see {@link IntegerSize}'s own Javadoc for the identical situation).
 */
public record TypeDefinition(Optional<TypeRef> source, TypeKind kind,
                              List<String> supertypes, List<String> subtypes,
                              Optional<Boolean> disjoint, Top body, @Unbound Optional<SourcePosition> position,
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
                           List<String> supertypes, List<String> subtypes, Optional<Boolean> disjoint, Top body,
                           Optional<SourcePosition> position) {
        this(source, kind, supertypes, subtypes, disjoint, body, position, Annotations.empty());
    }

    /** Same as the canonical constructor, {@code position} defaulted to absent -- every existing caller that doesn't know its own source position. */
    public TypeDefinition(Optional<TypeRef> source, TypeKind kind,
                           List<String> supertypes, List<String> subtypes, Optional<Boolean> disjoint, Top body) {
        this(source, kind, supertypes, subtypes, disjoint, body, Optional.empty());
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
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), List.of(),
                Optional.empty(), body);
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
                List.of(), Optional.empty(), new Reference(target));
    }

    /** A copy of this definition with {@code body} replaced -- every other component unchanged. */
    public TypeDefinition withBody(Top body) {
        return new TypeDefinition(source, kind, supertypes, subtypes, disjoint, body,
                position, annotations);
    }

    /** A copy of this definition with {@code position} replaced -- every other component unchanged. */
    public TypeDefinition withPosition(Optional<SourcePosition> position) {
        return new TypeDefinition(source, kind, supertypes, subtypes, disjoint, body,
                position, annotations);
    }

    /** A copy of this definition with {@code annotations} replaced -- every other component unchanged. */
    public TypeDefinition withAnnotations(Annotations annotations) {
        return new TypeDefinition(source, kind, supertypes, subtypes, disjoint, body,
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
                && Objects.equals(disjoint, other.disjoint)
                && Objects.equals(body, other.body);
    }

    /** Excludes {@code position} -- see this class's own Javadoc for why. */
    @Override
    public int hashCode() {
        return Objects.hash(source, kind, supertypes, subtypes, disjoint, body);
    }
}
