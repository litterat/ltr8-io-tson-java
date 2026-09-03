package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * [TSON-SCHEMA] §8.3's use-site reference flattening: a type position naming a {@code REFERENCE} entry is
 * rewritten to the first entry in the chain that is not one, and the name the author wrote is preserved on
 * the reference as {@code @alias}. meta-kernel's own resolved fixture states the rule in one line --
 * "Reference-kind names at type positions are flattened with @alias" -- and spells the result {@code type:
 * @alias:field_name token}.
 *
 * <p><b>What it buys is single-level identity.</b> §8.2 compares an instantiation's {@code source} as a
 * flat application, so a use site that still names an alias would have to be chased before two of them
 * could be told apart. Flattening moves that walk to schema-load time, once per use site, and the {@code
 * @alias} is what keeps the author's own word recoverable afterwards -- a diagnostic, a renderer or a
 * writer that says {@code field_name} where the author wrote {@code field_name}.
 *
 * <p><b>Only use sites.</b> An alias entry's own {@code source} and its {@link Reference} target are left
 * exactly as resolved: {@code doc => documentation} keeps its single hop even though {@code documentation}
 * is itself a reference to {@code text}. The chain has to remain walkable for anything that wants the
 * intermediate names, and the entry is what records it -- flattening there would erase the alias rather
 * than relocate it. {@code supertypes}/{@code subtypes} are name lists with no annotation channel and are
 * likewise untouched; they are resolver-managed indexes, not use sites.
 *
 * <p>Runs after materialisation so an instantiation's minted entry is in the namespace to be flattened
 * past: an alias to an application ({@code string_triple => vector<text, 3>}) is a {@code REFERENCE} like
 * any other, and a use of it lands on the instantiation with {@code @alias:string_triple}.
 *
 * <p><b>The walk carries the annotations of the entries it passes through.</b> A declaration's annotations
 * are reachable from a use site only while the use site names that declaration, and flattening is what stops
 * it naming one: {@code @bytes_encoding:HEX digest => bytes} is an alias, so every use of {@code digest}
 * becomes a use of {@code bytes} and the directive it was declared with becomes unreachable -- where the same
 * intent written as a refinement ({@code digest => !bytes ^ { length: 4 }}) keeps a {@code supertypes} edge
 * and is found. Two spellings of one intent, one of which silently reads base64. So each dropped hop's
 * annotations are appended to the use site, nearest hop first, after the {@code @alias} that names the first
 * of them.
 *
 * <p><b>Nearest-first and additive</b>, the same rule a restated field's annotations follow
 * ({@code DefinitionResolver.merged}): [TSON-DATA] §3.1 makes a name repeatable on one value, so this is
 * concatenation rather than replacement, and every first-occurrence lookup then reads the hop nearest the use
 * site. The terminal's own annotations are <em>not</em> carried -- the reference names it, so anything
 * consulting them can look it up, and copying them would put one fact in two places.
 *
 * <p><b>What travels is a directive, not every annotation</b> ({@link #CARRIED}). An annotation earns the
 * walk when carrying it changes how a value at that position is <em>read</em>; documentation is about the
 * declaration and stays there. Carrying everything was built first and measured, and it is wrong in a way
 * only the measurement shows: across all three bundled schemas exactly one annotation travels, meta-kernel's
 * group {@code @doc} on {@code type_name} ("Identifier roles -- distinct naming positions referencing the
 * identifier primitive"), which then documents four unrelated positions including {@code schema}'s own key
 * type. One sentence describing three declarations, restated as the documentation of an array's element.
 *
 * <p><b>The set is enumerated here because §6 gives no way to declare it.</b> An annotation is declared with
 * a type ({@code bytes_encoding => @annotation base_encoding}) and nothing else, so nothing in a schema says
 * whether it describes the declaration or directs how values of it are read. That is the same gap {@code
 * SPEC-FEEDBACK.md} #25(a) reports for checked annotations, met from the other side, and until §6 says which
 * annotations are positional an implementation can only carry the ones it acts on.
 *
 * <p><b>Derived markers do not travel either</b>, and would not even if the set were open: {@code @alias} and
 * {@code @synthetic} are §8.2 facts about the entry that carries them -- where a name came from, and that a
 * resolver minted it -- so carrying them onto a use site would assert them of a position that is neither.
 */
final class ReferenceFlattener {

    private static final String ALIAS = "alias";

    /**
     * The annotations a dropped hop hands to the use site: those that direct how a value at that position is
     * read. {@code @bytes_encoding} is the whole set today, being the only annotation in the meta layer with
     * per-position force -- see this class's own note on why the set is enumerated rather than declared.
     */
    private static final Set<String> CARRIED = Set.of("bytes_encoding");

    private ReferenceFlattener() {
    }

    /**
     * {@code entries} with every use site flattened. {@code namespace} is what chains are walked through --
     * the whole schema including merged imports, since an alias may be imported while the use site is local.
     */
    static Map<String, TypeDefinition> flatten(Map<String, TypeDefinition> entries,
                                                Map<String, TypeDefinition> namespace, Set<String> minted,
                                                Function<String, Annotations> declared) {
        Map<String, TypeDefinition> flattened = new LinkedHashMap<>();
        entries.forEach((name, definition) ->
                flattened.put(name, flattenEntry(definition, namespace, minted, declared)));
        return flattened;
    }

    /** One entry's body, or the entry unchanged where nothing in it moved. */
    private static TypeDefinition flattenEntry(TypeDefinition definition, Map<String, TypeDefinition> namespace,
                                                Set<String> minted, Function<String, Annotations> declared) {
        if (definition.body() instanceof Reference) {
            return definition; // an alias entry records the hop; see this class's own note
        }
        Top body = MetaRefs.mapBodyRefs(definition.body(), ref -> flattenRef(ref, namespace, minted, declared));
        return body == definition.body() ? definition : definition.withBody(body);
    }

    /** One type-ref, its own arguments flattened first so a nested alias moves too. */
    private static TypeRef flattenRef(TypeRef ref, Map<String, TypeDefinition> namespace, Set<String> minted,
                                       Function<String, Annotations> declared) {
        TypeRef withArguments = flattenArguments(ref, namespace, minted, declared);
        String terminal = terminalName(ref.name(), namespace, minted);
        if (terminal.equals(ref.name())) {
            return withArguments;
        }
        Annotations annotations = plusAlias(withArguments.annotations(), ref.name());
        annotations = plusCarried(annotations, ref.name(), terminal, namespace, minted, declared);
        return new TypeRef(terminal, withArguments.arguments(), annotations);
    }

    /** {@code annotations} with {@code @alias:written} added -- the carrier is immutable, so this rebuilds it. */
    private static Annotations plusAlias(Annotations annotations, String written) {
        Annotations.Builder builder = new Annotations.Builder();
        annotations.values().forEach(builder::add);
        return builder.add(new Annotation(ALIAS, Optional.of(written))).build();
    }

    /**
     * {@code annotations} followed by those of every hop this use site is being flattened past, nearest
     * first -- both of §6's declaration positions per hop, the key's before the definition's, since the key
     * is the position an author reaches for and the two are never in conflict.
     *
     * <p>Walks the same chain {@link #terminalName} did rather than sharing its loop: that method answers a
     * question every use site asks and most answer with "no hop at all", and threading a collector through
     * it would allocate on the common path to serve the rare one.
     */
    private static Annotations plusCarried(Annotations annotations, String written, String terminal,
            Map<String, TypeDefinition> namespace, Set<String> minted,
            Function<String, Annotations> declared) {
        Annotations.Builder builder = null;
        Set<String> walked = new LinkedHashSet<>();
        String current = written;
        while (!current.equals(terminal) && walked.add(current)) {
            TypeDefinition hop = namespace.get(current);
            if (hop == null || !(hop.body() instanceof Reference reference)) {
                break;
            }
            for (Annotation carried : carriedFrom(declared.apply(current), hop.annotations())) {
                if (builder == null) {
                    builder = new Annotations.Builder();
                    annotations.values().forEach(builder::add);
                }
                builder.add(carried);
            }
            if (minted.contains(current) || !reference.target().arguments().isEmpty()) {
                break;
            }
            current = reference.target().name();
        }
        return builder == null ? annotations : builder.build();
    }

    /** One hop's two annotation positions, filtered to {@link #CARRIED} -- see this class's own note. */
    private static List<Annotation> carriedFrom(Annotations key, Annotations definition) {
        List<Annotation> carried = new java.util.ArrayList<>();
        for (Annotations written : new Annotations[] { key, definition }) {
            if (written != null) {
                written.values().stream().filter(a -> CARRIED.contains(a.name())).forEach(carried::add);
            }
        }
        return carried;
    }

    private static TypeRef flattenArguments(TypeRef ref, Map<String, TypeDefinition> namespace,
                                             Set<String> minted, Function<String, Annotations> declared) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = ref.arguments().stream()
                .map(argument -> argument instanceof TypeArgument.Ref nested
                        ? new TypeArgument.Ref(flattenRef(nested.ref(), namespace, minted, declared))
                        : argument)
                .toList();
        return arguments.equals(ref.arguments()) ? ref
                : new TypeRef(ref.name(), arguments, ref.annotations());
    }

    /**
     * The first name in {@code name}'s reference chain that is not a {@code REFERENCE} entry, or {@code
     * name} itself when it is not one. A cycle stops at the name that closes it rather than spinning: an
     * unsatisfiable alias loop is {@code TsonSchemaLinker}'s verdict to give, not this pass's.
     */
    private static String terminalName(String name, Map<String, TypeDefinition> namespace, Set<String> minted) {
        Set<String> walked = new LinkedHashSet<>();
        String current = name;
        while (walked.add(current)) {
            TypeDefinition definition = namespace.get(current);
            if (definition == null || definition.kind() != TypeKind.REFERENCE
                    || !(definition.body() instanceof Reference reference)) {
                return current;
            }
            // Stop *at* a materialised instantiation rather than walking through it. This model gives one an
            // extra hop the spec's does not -- a REFERENCE entry over the form that holds the shape -- and
            // that entry is the thing §8.2 keys identity on, recording the flattened application in its own
            // source. Collapsing past it would erase the instantiation and leave a use site naming a form
            // nobody wrote. An author's alias *to* an application still flattens onto it, which is §8.3's
            // own worked example (`type: @alias:string_triple array_ranged_text_9d4`).
            if (minted.contains(current)) {
                return current;
            }
            // An argument-bearing target is an application, not a further hop -- it names a template plus the
            // arguments this alias binds, and there is no entry at the end of it until materialisation mints
            // one. Only a template's own body can hold one, and a template is never a use site.
            if (!reference.target().arguments().isEmpty()) {
                return current;
            }
            current = reference.target().name();
        }
        return current;
    }
}
