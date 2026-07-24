package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code type_definition} record, resolved (Part 2 §4, §8.1) -- what every
 * schema declaration ultimately resolves to. {@code kind} is REQUIRED with no default and always
 * appears in output; {@code source}/{@code disjoint} are genuinely OPTIONAL ({@code
 * Optional<TypeRef>}/{@code Optional<Boolean>}) and omitted from written output when absent, the
 * same as any other {@code Optional}-wrapped scalar/record field bound through plain {@code
 * TsonMapper.toTson}. {@code parameters}/{@code supertypes}/{@code subtypes} are conceptually
 * OPTIONAL in the kernel too ({@code [type_name]?} etc.), but modeled here as a bare, always-present
 * {@code List} rather than {@code Optional<List<...>>} -- {@code tson-bind} doesn't support an
 * {@code Optional} wrapping a parameterized collection type yet, so there's no way to opt an empty
 * list into the same omit-when-absent treatment; it writes as {@code []} instead. Likewise {@code
 * constructor}, a bare {@code boolean}, always appears (as {@code false}) rather than being omitted
 * at its nominal default -- a hand-written writer could special-case "omit when at default" for
 * these; plain generic binding has no such concept beyond {@code Optional.empty()}/{@code null}.
 * See {@code TsonSchema}'s and {@code SchemaResolverTest}'s own notes for what this means in
 * practice: written output is structurally faithful but more verbose than the non-normative
 * {@code meta-kernel-resolved.tn1} fixture's own hand-authored, terser conventions.
 */
public record TypeDefinition(Optional<TypeRef> source, TypeKind kind, List<String> parameters,
                              boolean constructor, List<String> supertypes, List<String> subtypes,
                              Optional<Boolean> disjoint, Top body) {

    public TypeDefinition {
        parameters = List.copyOf(parameters);
        supertypes = List.copyOf(supertypes);
        subtypes = List.copyOf(subtypes);
    }

    /** A fresh (non-constructor, no source/supertypes/parameters) PRODUCT definition -- {@code integer_size}'s own shape. */
    public static TypeDefinition product(Top body) {
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false, List.of(), List.of(),
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
     * A reference definition whose target may itself carry arguments -- a fully-bound template
     * application like {@code array_min<T, N>} (§5.10). Per §5.10/§8.2 the resolved {@code
     * body.target} should point at a *materialised instantiation entry*'s internal name, not at the
     * application itself -- this resolver doesn't materialise instantiation entries yet, so {@code
     * target} is reused as both {@code source} and (as a placeholder) {@code body.target} until
     * that exists.
     */
    public static TypeDefinition reference(TypeRef target) {
        return new TypeDefinition(Optional.of(target), TypeKind.REFERENCE, List.of(), false, List.of(),
                List.of(), Optional.empty(), new Reference(target));
    }
}
