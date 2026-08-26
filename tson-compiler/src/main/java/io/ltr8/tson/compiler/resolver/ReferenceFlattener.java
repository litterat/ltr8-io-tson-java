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
 */
final class ReferenceFlattener {

    private static final String ALIAS = "alias";

    private ReferenceFlattener() {
    }

    /**
     * {@code entries} with every use site flattened. {@code namespace} is what chains are walked through --
     * the whole schema including merged imports, since an alias may be imported while the use site is local.
     */
    static Map<String, TypeDefinition> flatten(Map<String, TypeDefinition> entries,
                                                Map<String, TypeDefinition> namespace, Set<String> minted) {
        Map<String, TypeDefinition> flattened = new LinkedHashMap<>();
        entries.forEach((name, definition) -> flattened.put(name, flattenEntry(definition, namespace, minted)));
        return flattened;
    }

    /** One entry's body, or the entry unchanged where nothing in it moved. */
    private static TypeDefinition flattenEntry(TypeDefinition definition, Map<String, TypeDefinition> namespace,
                                                Set<String> minted) {
        if (definition.body() instanceof Reference) {
            return definition; // an alias entry records the hop; see this class's own note
        }
        Top body = TemplateMaterialiser.mapBodyRefs(definition.body(), ref -> flattenRef(ref, namespace, minted));
        return body == definition.body() ? definition : definition.withBody(body);
    }

    /** One type-ref, its own arguments flattened first so a nested alias moves too. */
    private static TypeRef flattenRef(TypeRef ref, Map<String, TypeDefinition> namespace, Set<String> minted) {
        TypeRef withArguments = flattenArguments(ref, namespace, minted);
        String terminal = terminalName(ref.name(), namespace, minted);
        if (terminal.equals(ref.name())) {
            return withArguments;
        }
        return new TypeRef(terminal, withArguments.arguments(), plusAlias(withArguments.annotations(), ref.name()));
    }

    /** {@code annotations} with {@code @alias:written} added -- the carrier is immutable, so this rebuilds it. */
    private static Annotations plusAlias(Annotations annotations, String written) {
        Annotations.Builder builder = new Annotations.Builder();
        annotations.values().forEach(builder::add);
        return builder.add(new Annotation(ALIAS, Optional.of(written))).build();
    }

    private static TypeRef flattenArguments(TypeRef ref, Map<String, TypeDefinition> namespace,
                                             Set<String> minted) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = ref.arguments().stream()
                .map(argument -> argument instanceof TypeArgument.Ref nested
                        ? new TypeArgument.Ref(flattenRef(nested.ref(), namespace, minted))
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
