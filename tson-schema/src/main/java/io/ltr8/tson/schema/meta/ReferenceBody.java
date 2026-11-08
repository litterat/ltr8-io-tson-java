package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;
import io.ltr8.tson.schema.atom.AtomNarrowing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * An instance of the kernel's {@code reference_type} constructor, resolved ([TSON-SCHEMA] §4.2, §5.12): the atom whose
 * values are names, each naming an entry whose value's type, past its alias hops, IS-A {@link #of}.
 * {@code meta.ref<record>} and {@code !meta.reference_type { of: record  allow_sibling: false }} are one instance; the
 * template is a spelling, and the slot's meaning comes from {@link #of}.
 *
 * <p><b>{@link #allowSibling} says whether a sibling reference ({@code kind!}) is a value</b> ([TSON-SCHEMA] §5.2,
 * §8.1). The grammar reads one wherever a reference stands, and the facet admits or refuses it, as {@code allow_nan}
 * admits or refuses NaN at a {@code float_type}: true by default, the widest reading, and withdrawn by a refinement.
 * Only {@code meta.field_type_ref}, which types a record field's {@code type}, keeps it; {@code meta.type_ref} and
 * {@code meta.ref<T>} set it false. In bind mode the same rule is the component type: a {@link Reference} component
 * holds a sibling reference, a {@link TypeRef} one never does.
 *
 * <p><b>What {@link #of} names decides what the slot holds.</b> Where it is a constructor ({@code top} or IS-A it),
 * the values it admits are types, and the slot holds a type's name, delivered to a host as a {@link TypeRef} that
 * takes part in structural identity. Where it is any other type, the values it admits are values, and the slot holds
 * a value's qualified name, delivered as the referent ({@link Ref}). That fact is the namespace's, so the linker
 * derives it ({@link #namesTypes}).
 *
 * <p><b>The {@link #index} is derived and never written.</b> It is absent on a declaration and computed at link over
 * the governing schema's closure: whether {@code of} names a constructor, and, for one that does, the names it admits,
 * sorted; for one that does not, what a qualified name reaches there ({@link Values}). Membership and reach are the
 * linker's judgement, so resolved output carries {@code of} alone.
 *
 * <p><b>A refinement narrows {@link #of}</b> ([TSON-SCHEMA] §5.7): {@code !meta.type_ref ^ { of: unicode.identifier_type
 * }} is {@code meta.identifier_ref}, its {@code of} IS-A the source's, so every name it admits the source admits too,
 * and it is IS-A its source. Whether one name IS-A another is the namespace's, so the rule ({@link #ofNarrowing}) takes
 * that relation from the linker.
 *
 * <p><b>Equality is {@link #of}, {@link #allowSibling} and the chain, never the index.</b> Two schemas that both apply
 * {@code meta.ref<uuid_type>} hold one instance with different members, and an importer that re-derives an entry an
 * import already minted unifies with it rather than colliding (§8.2).
 */
@Typename(name = "meta.reference_type")
public record ReferenceBody(TypeChain chain, String of, @Field("allow_sibling") boolean allowSibling,
                            @Unbound Optional<Index> index) implements Atom {

    /**
     * What the linker derives for one reference: whether {@code of} names a constructor, so the values admitted are
     * types, and the members where it does -- the names of the closure the slot admits, sorted -- with the entry each
     * application the closure closed denotes, an expansion's included, so a value written as an application is
     * judged through the entry it names, and the closure's entries, which a reference read here names ({@link
     * #closure}). {@code shown} is the members as a message names them: a minted entry by the application that
     * closed to it, as written, and one with no spelling left out. {@code values}, for a reference that names
     * values, is what a qualified name reaches in that closure ({@link Values}); empty for one that names types, and
     * for a link of a resolution in progress, whose pending entries hold no value yet.
     */
    public record Index(boolean namesTypes, List<String> members, Map<TypeRef, String> applications,
                        Map<String, Top> entries, List<String> shown, @Unbound Optional<Values> values) {

        public Index {
            members = List.copyOf(members);
            shown = List.copyOf(shown);
            applications = Map.copyOf(applications);
            Objects.requireNonNull(entries, "entries");
            Objects.requireNonNull(values, "values");
        }
    }

    /**
     * What a reference that names values reaches by a qualified name, over the closure of the schema that linked it
     * ([TSON-SCHEMA] §4.2, §5.12): the first segment an entry of that closure, each further one a member of a nested
     * schema map, a key of a namespace value or a field of a record value, and the value reached one whose type IS-A
     * {@link #of}. The linker supplies it, as it judges a value body's names, so a name a data document writes is
     * judged by the rule a schema's own is.
     *
     * <p>Never bound: it is a component of the derived {@link Index} only, which no document writes. It is marked a
     * union so the binder, which describes every component of a model record, an {@code @Unbound} one included, has
     * a shape to describe it by.
     */
    @FunctionalInterface
    @Union
    public interface Values {

        /**
         * The value {@code name} reaches: a value entry's value, or the key's or field's value a further segment
         * selects.
         *
         * @throws io.ltr8.tson.base.SchemaValidationException where the name reaches no value of {@link #of}, its
         *         message saying which step failed and opening with {@code where}
         * @throws UnsupportedOperationException where a step is one this library does not yet follow
         */
        Object reach(String where, TypeRef name);
    }

    @Record
    public ReferenceBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(of, "of");
        index = index == null ? Optional.empty() : index;
    }

    /**
     * A reference as an author declares it, placed in no chain -- the linker derives its {@link #index} -- admitting
     * a sibling reference or refusing one as {@code allowSibling} says.
     */
    public ReferenceBody(String of, boolean allowSibling) {
        this(TypeChain.EMPTY, of, allowSibling, Optional.empty());
    }

    /** A reference as {@code meta.ref<T>} declares it: {@code allow_sibling: false}. */
    public static ReferenceBody of(String of) {
        return new ReferenceBody(of, false);
    }

    @Override
    public ReferenceBody withChain(TypeChain chain) {
        return new ReferenceBody(chain, of, allowSibling, index);
    }

    /**
     * This reference with its derived index: {@code namesTypes}, the members sorted, the closure's applications, and
     * its entries.
     */
    public ReferenceBody linked(boolean namesTypes, List<String> members, Map<TypeRef, String> applications,
                                Map<String, Top> entries, List<String> shown) {
        return linked(namesTypes, members, applications, entries, shown, Optional.empty());
    }

    /** {@link #linked(boolean, List, Map, Map, List)} with what a name this reference holds reaches ({@link Values}). */
    public ReferenceBody linked(boolean namesTypes, List<String> members, Map<TypeRef, String> applications,
                                Map<String, Top> entries, List<String> shown, Optional<Values> values) {
        return new ReferenceBody(chain, of, allowSibling, Optional.of(new Index(namesTypes,
                members.stream().sorted().toList(), applications, entries, shown.stream().sorted().toList(), values)));
    }

    /**
     * What a name this reference holds reaches ({@link Index#values}), or empty where it names types or is judged
     * nowhere a read can ask: unlinked, or linked while its schema is still resolving.
     */
    public Optional<Values> values() {
        return index.flatMap(Index::values);
    }

    /**
     * Whether {@code of} names a constructor, so a value is a type's name. True where linking has not run, which
     * every reader of a value reads after: an unlinked body then reads as a kind with no members.
     */
    public boolean namesTypes() {
        return index.map(Index::namesTypes).orElse(true);
    }

    /** The names a slot that names types admits, or none where linking has not computed them. */
    public List<String> memberNames() {
        return index.map(Index::members).orElse(List.of());
    }

    /** The members as a message names them -- a minted one by its application ({@link Index#shown}). */
    public List<String> shownMembers() {
        return index.map(Index::shown).orElse(List.of());
    }

    /**
     * The entry {@code reference} denotes in the closure this reference was linked over: a name is its own entry, and
     * an application resolves bottom-up by structural identity ([TSON-SCHEMA] §8.2) -- each argument that is itself an
     * application first, to the entry the closure closed it to, then the application over those entries, against
     * the applications the closure closed. Empty where nothing in the closure closed it.
     */
    public Optional<String> entryOf(TypeRef reference) {
        if (index.isEmpty()) {
            return Optional.empty();
        }
        if (reference.arguments().isEmpty()) {
            return Optional.of(reference.name());
        }
        Map<TypeRef, String> applications = index.get().applications();
        String direct = applications.get(reference);
        if (direct != null) {
            return Optional.of(direct);
        }
        List<TypeArgument> closed = new ArrayList<>(reference.arguments().size());
        for (TypeArgument argument : reference.arguments()) {
            if (argument instanceof TypeArgument.Ref nested && !nested.ref().arguments().isEmpty()) {
                Optional<String> inner = entryOf(nested.ref());
                if (inner.isEmpty()) {
                    return Optional.empty();
                }
                closed.add(new TypeArgument.Ref(TypeRef.of(inner.get())));
            } else {
                closed.add(argument);
            }
        }
        return Optional.ofNullable(applications.get(TypeRef.of(reference.name(), closed)));
    }

    /**
     * The closure a reference read against this one finds its referent in ({@link TypeRef#referent}): the value of
     * the entry it denotes ({@link #entryOf}), over the entries the linker indexed. {@link TypeRef.Closure#NONE}
     * where this reference is not linked.
     */
    public TypeRef.Closure closure() {
        return index.<TypeRef.Closure>map(found -> reference -> entryOf(reference)
                .map(found.entries()::get).map(Object.class::cast)).orElse(TypeRef.Closure.NONE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Whether a refinement's {@code of} narrows the source's is an IS-A question about two names, which only the
     * namespace they resolve in answers: that half of the rule is {@link #ofNarrowing}, asked at link. Here a
     * reference refines only a reference, and {@link #allowSibling} is a permission: a refinement may withdraw it,
     * never grant it back.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof ReferenceBody other)) {
            return List.of("refines a reference with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_sibling", allowSibling, other.allowSibling);
        return List.copyOf(violations);
    }

    /**
     * [TSON-SCHEMA] §5.7's rule for {@code of}, this body being the source: the refined {@code of} is the source's or
     * IS-A it, so every name the refinement admits the source admits too. {@code isA} answers whether its first name
     * IS-A its second in the namespace both resolve in, a name being IS-A itself; an empty list means {@code refined}
     * narrows. A widening, and an {@code of} unrelated to the source's, is refused.
     */
    public List<String> ofNarrowing(ReferenceBody refined, BiPredicate<String, String> isA) {
        return isA.test(refined.of, of) ? List.of() : List.of("of '" + refined.of + "' is not '" + of
                + "' or IS-A it, so the refinement admits names the source refuses -- a refinement narrows 'of' (§5.7)");
    }

    /** Compares {@link #of}, {@link #allowSibling} and the chain, never the index -- see this class's Javadoc. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ReferenceBody other && of.equals(other.of) && allowSibling == other.allowSibling
                && chain.equals(other.chain);
    }

    /** {@link #of}, {@link #allowSibling} and the chain, as {@link #equals} compares them. */
    @Override
    public int hashCode() {
        return 31 * (31 * of.hashCode() + Boolean.hashCode(allowSibling)) + chain.hashCode();
    }

    /** What {@link #equals} compares: the derived index is no part of what this is. */
    @Override
    public String toString() {
        return "ReferenceBody[chain=" + chain + ", of=" + of + ", allowSibling=" + allowSibling + "]";
    }
}
