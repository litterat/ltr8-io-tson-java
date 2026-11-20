package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeSlots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The fields each entry restates over a source its closed chain does not reach ([TSON-SCHEMA] §5.8, §5.9), collected
 * for the linker to judge ({@link TsonSchema#restatements()}): the resolver cannot ask IS-A, the names a restated
 * type holds being ready only once every entry is.
 *
 * <p><b>A parametric record's restatements travel with its applications.</b> Its own hold its parameters, and every
 * place one of its applications becomes part of an entry -- absorbed at an operand, closed into a declaration, closed
 * in place at a slot -- makes them that entry's, substituted. So {@code wrap<text>}, over {@code wrap => <T> box<T> &
 * { v: int32 }}, is judged against {@code box<text>}'s {@code v} wherever it stands, whether or not a declaration
 * names {@code box<text>}. The transfers are settled to a fixed point once every entry has closed, since a parametric
 * record gains restatements from the applications materialisation closes inside it after another entry absorbed it.
 */
final class Restatements {

    private final Map<String, Set<TsonSchema.Restatement>> held = new LinkedHashMap<>();
    private final Set<Transfer> transfers = new LinkedHashSet<>();

    /** {@code head}'s restatements, substituted by {@code bindings}, are {@code entry}'s. */
    private record Transfer(String entry, String head, Map<String, TypeRef> bindings) {
    }

    /** Seeded with the imports' restatements, each schema's settled when it resolved. */
    Restatements(Map<String, List<TsonSchema.Restatement>> imported) {
        imported.forEach((name, restatements) -> held.put(name, new LinkedHashSet<>(restatements)));
    }

    void restates(String entry, TsonSchema.Restatement restatement) {
        held.computeIfAbsent(entry, name -> new LinkedHashSet<>()).add(restatement);
    }

    /** Records that an application of the parametric record {@code head}, bound by {@code bindings}, is in {@code entry}. */
    void absorbs(String entry, String head, Map<String, TypeRef> bindings) {
        if (entry != null && !entry.equals(head)) {
            transfers.add(new Transfer(entry, head, Map.copyOf(bindings)));
        }
    }

    /**
     * Every entry's restatements, each transfer applied until none adds one, and then each type's applications closed
     * by {@code closing}. Bounded: an argument is a name and a recursion is regular (§5.10.1), so the substituted
     * restatements a schema reaches are finitely many.
     *
     * <p><b>Compared closed, as an entry's own fields are.</b> Recorded before materialisation, an application is
     * still as written, so {@code item<name>} would differ from {@code item<text>} over {@code name => text}, where
     * the two close to one record (§8.2). A restatement whose types do not close is dropped: the application is
     * refused where it is written, and a second verdict on it would name its consequence.
     */
    Map<String, List<TsonSchema.Restatement>> settled(UnaryOperator<TypeExpr> closing) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Transfer transfer : transfers) {
                Set<TsonSchema.Restatement> from = held.get(transfer.head());
                if (from == null) {
                    continue;
                }
                Set<TsonSchema.Restatement> into = held.computeIfAbsent(transfer.entry(), name -> new LinkedHashSet<>());
                for (TsonSchema.Restatement restatement : List.copyOf(from)) {
                    changed |= into.add(substituted(restatement, transfer.bindings()));
                }
            }
        }
        Map<String, List<TsonSchema.Restatement>> settled = new LinkedHashMap<>();
        held.forEach((name, restatements) -> {
            List<TsonSchema.Restatement> closed = new ArrayList<>();
            for (TsonSchema.Restatement restatement : restatements) {
                try {
                    closed.add(new TsonSchema.Restatement(restatement.source(), restatement.field(),
                            restatement.inherited().withType(closing.apply(restatement.inherited().type())),
                            restatement.restated().withType(closing.apply(restatement.restated().type()))));
                } catch (SchemaValidationException | UnsupportedOperationException refused) {
                    // Refused where the application is written; see the method's Javadoc.
                }
            }
            settled.put(name, closed);
        });
        return settled;
    }

    private static TsonSchema.Restatement substituted(TsonSchema.Restatement restatement,
                                                      Map<String, TypeRef> bindings) {
        TypeSlots.Rewrite substitute = reference -> Substitution.reference(reference, bindings);
        return new TsonSchema.Restatement(TypeSlots.map(restatement.source(), substitute), restatement.field(),
                restatement.inherited().withType(TypeSlots.map(restatement.inherited().type(), substitute)),
                restatement.restated().withType(TypeSlots.map(restatement.restated().type(), substitute)));
    }
}
