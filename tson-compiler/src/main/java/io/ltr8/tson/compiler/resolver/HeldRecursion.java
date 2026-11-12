package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * [TSON-SCHEMA] §5.10.1's held recursion: an open record holding, where it would close in place, an application of a
 * recursive open record whose recursion does not pass back through it could never be closed, and is refused at its own
 * declaration.
 *
 * <p><b>Why it is decidable from the heads.</b> Closing an application ties a recursion only to the declaration closing
 * it, and an application at a slot is never looked up, so closing {@code R} ends only where every cycle the closing
 * reaches passes back through {@code R}. Regularity passes each parameter through unchanged and an argument is a name,
 * so a cycle of heads is a cycle of applications, and the graph of which open record holds which in place decides it.
 *
 * <p><b>Judged one hop out.</b> {@code R} is refused where it holds {@code S} itself and {@code S} lies on a cycle that
 * avoids {@code R}. A record reaching such a cycle only through another, {@code outer} holding {@code wrap<T>} over
 * {@code wrap} holding {@code node<T>}, has that other on its path one hop from the cycle, refused at its own
 * declaration; {@code outer} is a consequence, closing against the refused record's placeholder as an application of
 * any refused template does, and its remedy is {@code wrap}'s.
 *
 * <p><b>Fields only, not parents.</b> An applied parent's fields are absorbed into the record that composes it, so they
 * are its own and are walked as its fields; the parent itself is absorbed, never closed, and takes no part.
 */
final class HeldRecursion {

    /** Every entry visible to the schema, an import's included, resolved on demand. */
    private final Function<String, Top> lookup;

    /** Whether a name is this document's own declaration, which this resolution reports. */
    private final Predicate<String> local;

    private final Map<String, Optional<SchemaValidationException>> judged = new HashMap<>();
    private final Map<String, List<Held>> heldBy = new HashMap<>();

    HeldRecursion(Function<String, Top> lookup, Predicate<String> local) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.local = Objects.requireNonNull(local, "local");
    }

    /** One application of an open record held where it closes in place, under the field holding it. */
    private record Held(String field, boolean whole, TypeRef written, String head) {
    }

    /**
     * Whether an application of {@code head} closes against a placeholder: its open record is this document's own and
     * refused here, so the refusal is {@code head}'s to report and not its applier's.
     */
    boolean refusedLocally(String head) {
        return local.test(head) && refusal(head).isPresent();
    }

    /** The refusal of the open record {@code name}, or empty where it can be closed or is no open record. */
    Optional<SchemaValidationException> refusal(String name) {
        Optional<SchemaValidationException> known = judged.get(name);
        if (known != null) {
            return known;
        }
        Optional<SchemaValidationException> verdict = judge(name);
        judged.put(name, verdict);
        return verdict;
    }

    /**
     * Forgets every verdict: one reached while declarations were made ready reads the records as they were then, and
     * a record replaced by its placeholder since holds nothing.
     */
    void forget() {
        judged.clear();
        heldBy.clear();
    }

    private Optional<SchemaValidationException> judge(String name) {
        Top record = lookup.apply(name);
        if (!OpenEntry.isOpen(record)) {
            return Optional.empty();
        }
        for (Held held : held(name)) {
            if (!held.head().equals(name) && onCycleAvoiding(held.head(), name)) {
                return Optional.of(refused(name, OpenEntry.parameterNames(record), held));
            }
        }
        return Optional.empty();
    }

    /** Whether {@code from} reaches itself through the records it holds, never passing {@code avoiding}. */
    private boolean onCycleAvoiding(String from, String avoiding) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        held(from).forEach(held -> pending.push(held.head()));
        while (!pending.isEmpty()) {
            String head = pending.pop();
            if (head.equals(from)) {
                return true;
            }
            if (head.equals(avoiding) || !seen.add(head)) {
                continue;
            }
            held(head).forEach(held -> pending.push(held.head()));
        }
        return false;
    }

    private List<Held> held(String name) {
        List<Held> known = heldBy.get(name);
        if (known != null) {
            return known;
        }
        List<Held> found = new ArrayList<>();
        if (lookup.apply(name) instanceof RecordBody record && record.isOpen()) {
            record.fields().forEach((field, declared) -> heldIn(field, declared, found));
        }
        heldBy.put(name, found);
        return found;
    }

    private void heldIn(String field, RecordField declared, List<Held> found) {
        switch (declared.type()) {
            case TypeRef reference -> atSlot(field, true, reference, found);
            case TypeInline inline -> MetaRefs.mapRefs(inline.of(), new MetaRefs.Rewrite() {
                @Override
                public TypeRef atName(TypeRef reference) {
                    return reference;
                }

                @Override
                public TypeExpr atSlot(TypeRef reference) {
                    HeldRecursion.this.atSlot(field, false, reference, found);
                    return reference;
                }
            });
            case SiblingRef ignored -> {
            }
        }
    }

    /**
     * An application at a type slot: an open record's is held, its arguments being names; a constructor's lands each
     * argument at a slot of the construction, and one at a type slot closes there in turn.
     */
    private void atSlot(String field, boolean whole, TypeRef reference, List<Held> found) {
        if (reference.arguments().isEmpty()) {
            return;
        }
        String head = AliasChain.terminal(reference.name(), lookup);
        Top definition = lookup.apply(head);
        if (OpenEntry.isOpen(definition)) {
            found.add(new Held(field, whole, reference, head));
            return;
        }
        if (!ConstructionSugar.constructs(head, definition)) {
            return;
        }
        List<Boolean> inPlace = ConstructionSugar.slotsInPlace((RecordBody) definition, lookup);
        for (int i = 0; i < reference.arguments().size(); i++) {
            if (i < inPlace.size() && inPlace.get(i)) {
                atSlot(field, false, reference.arguments().get(i), found);
            }
        }
    }

    private static SchemaValidationException refused(String name, List<String> parameters, Held held) {
        String parameter = fresh(parameters);
        boolean closed = held.written().arguments().stream()
                .noneMatch(argument -> argument.arguments().isEmpty() && parameters.contains(argument.name()));
        String declared = closed
                ? TemplateMaterialiser.suggestedName(held.written()) + " => " + held.written().text()
                : "my_" + held.written().segments().getLast() + " => " + TypeRef.of(held.written().name(),
                        held.written().arguments().stream().map(argument -> TypeRef.of("...")).toList()).text();
        String spelled = held.written().text();
        String written = held.whole() ? "write '" + held.field() + ": " + parameter + "', '" + parameter + "' a parameter"
                + " of '" + name + "'" : "write a parameter '" + parameter + "' of '" + name + "' where '" + spelled
                + "' stands";
        return new SchemaValidationException("'" + name + "' holds '" + spelled + "' at '" + held.field()
                + "', an application of the recursive open record '" + held.head() + "', which closes in place "
                + "wherever '" + name + "' is applied; a recursion ties only to a declaration closing that record "
                + "itself (§5.10.1), so '" + name + "' could never be closed. Take the closed type as a parameter: "
                + written + ", and apply '" + name + "' to a declared application of '" + held.head() + "' ('"
                + declared + "', passed for '" + parameter + "')");
    }

    /** A parameter name the record does not declare, for the remedy. */
    private static String fresh(List<String> parameters) {
        String candidate = "N";
        for (int i = 2; parameters.contains(candidate); i++) {
            candidate = "N" + i;
        }
        return candidate;
    }
}
