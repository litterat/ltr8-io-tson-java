package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Chained;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a written parent contributes to an IS-A chain ([TSON-SCHEMA] §5.8): the one rule a closed declaration's
 * chain is built by at its declaration and a held record's when materialisation closes it.
 *
 * <p><b>A closed parent</b> contributes itself, the end of its reference chain (§8.3: the terminal is the same
 * type under another name, so IS-A reaches it) and that entry's own chain. <b>An application</b> denotes no entry
 * at the position that writes it -- its fields are absorbed by value -- so it contributes its template's
 * ancestors, and nothing for the template, which §5.10 makes no type. The edge to the entry a closed application
 * denotes is {@code SchemaResolver}'s to fold, once every declaration has resolved; until then the application is
 * kept in the chain's {@code parents}, the one list that holds applications.
 *
 * <p><b>A template's ancestors are its own chain's {@code supertypes}</b>: the closed entries its written parents
 * reach, computed when it was declared ({@code TypeChain}), so an imported template yields them as a local one
 * does.
 */
final class Ancestry {

    private Ancestry() {
    }

    /** A closed parent's contribution: itself, the end of its reference chain, and that entry's chain. */
    static List<String> ofParent(String parent, DefinitionGetter namespace) {
        List<String> names = new ArrayList<>();
        names.add(parent);
        String terminal = ReferenceChain.terminal(parent, namespace::definition);
        if (!terminal.equals(parent)) {
            names.add(terminal);
        }
        names.addAll(closedNames(namespace.definition(terminal)));
        return names;
    }

    /** The names {@code value}'s chain already holds -- all of it once resolution is done. */
    static List<String> closedNames(Top value) {
        return value == null ? List.of() : Chained.supertypesOf(value);
    }

    /**
     * What an application of the template {@code head} contributes: the ancestors its own written parents reach,
     * and nothing for the template itself. Empty for a head that is no template.
     */
    static List<String> ofTemplate(String head, DefinitionGetter namespace) {
        Top template = namespace.definition(head);
        return OpenEntry.isOpen(template) ? Chained.supertypesOf(template) : List.of();
    }

    /**
     * The chain a closed record's written parents reach: every closed parent's contribution and every
     * application's, deduplicated, with each application kept in {@code parents} for the fold.
     */
    static TypeChain chainOf(List<TypeRef> written, DefinitionGetter namespace) {
        List<TypeRef> applied = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (TypeRef parent : written) {
            if (!parent.arguments().isEmpty()) {
                names.addAll(ofTemplate(parent.name(), namespace));
                applied.add(parent);
                continue;
            }
            names.addAll(ofParent(parent.name(), namespace));
        }
        return TypeChain.of(List.copyOf(names)).withParents(applied);
    }
}
