package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Type;
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
 * ancestors, read from the template's held text, and nothing for the template, which §5.10 makes no type. The
 * edge to the entry a closed application denotes is {@code SchemaResolver}'s to fold, once every declaration has
 * resolved.
 *
 * <p><b>Read from the held text, not stored beside it.</b> A template composes {@code top} and carries no chain of
 * its own (SPEC-FEEDBACK.md #14); its {@code supertypes} slot is the parents as written, so an imported template
 * yields its ancestors the same way a local one does.
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

    /**
     * The bare names in {@code value}'s chain -- all of it once resolution is done, and during it the part that
     * already names entries: a parent still written as an application is waiting for {@code SchemaResolver}'s
     * fold and names no entry yet, its head being a template, which nothing is IS-A.
     */
    static List<String> closedNames(Top value) {
        return value instanceof Type type
                ? type.supertypes().stream().filter(parent -> parent.arguments().isEmpty()).map(TypeRef::name).toList()
                : List.of();
    }

    /**
     * What an application of the template {@code head} contributes: the ancestors its own written parents reach,
     * and nothing for the template itself. Empty for a head that is no template.
     */
    static List<String> ofTemplate(String head, DefinitionGetter namespace) {
        return ofTemplate(head, namespace, new LinkedHashSet<>());
    }

    private static List<String> ofTemplate(String head, DefinitionGetter namespace, Set<String> visiting) {
        if (!visiting.add(head) || !(namespace.definition(head) instanceof TemplateBody held)) {
            return List.of();
        }
        List<String> ancestors = new ArrayList<>();
        for (TypeRef parent : WireForm.writtenParents(HeldBody.of(held).application())) {
            if (!parent.arguments().isEmpty()) {
                ancestors.addAll(ofTemplate(parent.name(), namespace, visiting));
            } else if (!held.parameterNames().contains(parent.name())) {
                ancestors.addAll(ofParent(parent.name(), namespace));
            }
        }
        return ancestors;
    }

    /**
     * The chain a closed record's written parents reach: every closed parent's contribution and every
     * application's, deduplicated in order, with each application kept as written for the fold.
     */
    static List<TypeRef> chainOf(List<TypeRef> written, DefinitionGetter namespace) {
        List<TypeRef> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TypeRef parent : written) {
            if (!parent.arguments().isEmpty()) {
                ofTemplate(parent.name(), namespace).forEach(name -> add(chain, seen, name));
                chain.add(parent);
                continue;
            }
            ofParent(parent.name(), namespace).forEach(name -> add(chain, seen, name));
        }
        return List.copyOf(chain);
    }

    private static void add(List<TypeRef> chain, Set<String> seen, String name) {
        if (seen.add(name)) {
            chain.add(TypeRef.of(name));
        }
    }
}
