package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaRefusalException;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.NameRef;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.TypeExprDef;
import io.ltr8.tson.compiler.reader.PendingTypeReader;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Top;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One schema's declarations made ready in [TSON-SCHEMA] §3.4.1's wait order: the one
 * algorithm, whichever schema it runs over. The {@link DefinitionGetter} every lookup of a resolution goes through,
 * so an entry is made ready the first time anything asks for it, and the reader its own document's values are read
 * through. What differs between the kernel's bootstrap and every other schema is only what is ready before it starts
 * -- the pre-loaded set the bootstrap builds by hand, the import closure everywhere else -- and how one declaration is
 * made ready ({@link Readiness}).
 *
 * <ul>
 *   <li><b>The rule.</b> Reading a value against a type waits on that type being ready, and on each type a part of
 *       the value is read against as the read reaches it; a declaration waits as well on its refinement source, its
 *       {@code &} operands and the types of its annotations, an alias on its target. Each such wait is a lookup here
 *       made while another entry is being made ready ({@link #current()}), or a declaration a read through {@link
 *       #read} reaches: one ready already ({@link #reached}), or one not yet ready, which the read stops at.</li>
 *   <li><b>Reads.</b> Every value this document reads is read through the readers of a checkpoint, the imports and
 *       the entries ready so far ({@link Readers}), every declaration not yet ready compiling to a {@link
 *       PendingTypeReader}. A read that reaches what its readers lack stops ({@link PendingTypeReader.NotReady});
 *       the entry it names is made ready, as a wait of the entry being made ready, and the read starts over at a
 *       checkpoint that holds it. A held value reached through one binds as written: a reference and a held value
 *       order nothing.</li>
 *   <li><b>Cycles.</b> An entry reached again while it is being made ready waits on itself: a {@link
 *       SchemaValidationException} naming the entries on the cycle in wait order ({@code "'a' waits on 'b' waits on
 *       'a'"}).</li>
 *   <li><b>Wait depth.</b> The longest chain of waits from an entry, over the waits its resolution ran, is its wait
 *       depth: none for an entry that waits on nothing of this document, one more than the deepest entry it waits on
 *       otherwise. An import is ready before the resolution starts, so a wait ends at one. A chain past {@code
 *       maxWaitDepth} (§11.5) refuses the entry at its head with {@code LIMIT_EXCEEDED}, whichever order the chain was
 *       reached in: as soon as the entries being made ready form one, so recursion never runs deeper than the limit,
 *       and otherwise when the head is ready and its depth known.</li>
 * </ul>
 *
 * <p>Source order is never consulted for the verdict or the output: the order entries are reached in changes how
 * many compiles run, and nothing else.
 */
final class WaitOrder implements DefinitionGetter {

    /** How one declaration is made ready, and what is left in its place when that fails. */
    interface Readiness {

        /** The value {@code declaration} resolves to, read in this order: every lookup it makes goes through here. */
        Top resolve(String name, SchemaMap.Declaration declaration);

        /**
         * What making {@code name} ready failing with {@code error} leaves: {@code error} rethrown, or the failure
         * reported and a stand-in returned for its dependents to resolve against.
         */
        Top failed(String name, SchemaMap.Declaration declaration, RuntimeException error);
    }

    /** The readers of the entries ready now, {@code pending} naming the declarations not yet ready. */
    @FunctionalInterface
    interface Readers {

        /** In object-binding mode where {@code tree} is false, in tree mode where it is true. */
        TsonCompiledSchema compile(Set<String> pending, boolean tree);
    }

    private final String identity;
    private final Map<String, Top> entries;
    private final Map<String, SchemaMap.Declaration> declarations;
    private final int maxWaitDepth;
    private final Readers readers;
    private Readiness readiness;

    /** The entries being made ready, innermost last: each waits on the next. */
    private final SequencedSet<String> resolving = new LinkedHashSet<>();

    /** For each entry being made ready, the entries of this document it has waited on so far. */
    private final Map<String, Set<String>> waits = new HashMap<>();

    /** Each ready declaration's wait depth, and the wait its deepest chain runs through. */
    private final Map<String, Integer> depths = new HashMap<>();
    private final Map<String, String> deepest = new HashMap<>();

    /** The head of a chain refused as too deep while its entries were being made ready; {@code null} otherwise. */
    private String refusedHead;

    /**
     * The readers of the latest checkpoint; {@code null} until a read needs them. Kept across a join, since every fact
     * they take from the closure is reached by a name or a pin at which a read stops; dropped when a read stops, and
     * once every entry is ready ({@link #settled}).
     */
    private TsonCompiledSchema bound;
    private TsonCompiledSchema tree;

    /** The readers the read in progress holds, innermost; {@code null} outside a read. */
    private TsonCompiledSchema reading;

    /**
     * @param identity     the canonical identity of the document being resolved, which its entries' readers locate
     *                     their declarations by
     * @param entries      the namespace, which this fills as each declaration is made ready: everything ready
     *                     before the resolution starts already in it, and whatever else a resolution adds (an
     *                     application it closes) put in by that resolution
     * @param declarations this document's declarations, by name
     * @param maxWaitDepth [TSON-SCHEMA] §11.5's wait depth
     * @param readers      compiles the readers of the entries ready now
     */
    WaitOrder(String identity, Map<String, Top> entries, Map<String, SchemaMap.Declaration> declarations,
              int maxWaitDepth, Readers readers) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.declarations = Objects.requireNonNull(declarations, "declarations");
        this.maxWaitDepth = maxWaitDepth;
        this.readers = Objects.requireNonNull(readers, "readers");
    }

    /**
     * Supplies how a declaration is made ready; called once, before the first lookup. Separate from construction
     * because the two are built mutually: a {@link DefinitionResolver} looks up through this order, and this order
     * resolves through it.
     */
    void readyWith(Readiness readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    /** The entry being made ready innermost, or {@code null} when none is. */
    String current() {
        return resolving.isEmpty() ? null : resolving.getLast();
    }

    /**
     * Makes every declaration ready. Those whose declaration reads no value -- a record, a composition, an alias, a
     * template -- and those {@code first} names go first, so the first compile a read needs already holds every head
     * a read applies; the reads then follow their own waits. The order changes how many compiles run, and nothing
     * else.
     */
    void readyAll(Predicate<String> first) {
        for (SchemaMap.Declaration declaration : declarations.values()) {
            if (!reads(declaration) || first.test(declaration.name())) {
                definition(declaration.name());
            }
        }
        for (String name : List.copyOf(declarations.keySet())) {
            definition(name);
        }
        settled();
    }

    /** Whether {@code declaration} is read against a head: a closed instance or an atom refinement. */
    static boolean reads(SchemaMap.Declaration declaration) {
        return declaration.typeDef() instanceof Instance instance ? instance.typeParams().isEmpty()
                : declaration.typeDef() instanceof AtomRefinement;
    }

    @Override
    public Top definition(String name) {
        Top ready = entries.get(name);
        if (ready != null) {
            waited(name);
            return ready;
        }
        SchemaMap.Declaration declaration = declarations.get(name);
        if (declaration == null) {
            return null; // a name no entry holds yet: a reference the linker judges
        }
        if (resolving.contains(name)) {
            throw cycle(name, resolving);
        }
        waited(name);
        if (resolving.size() > maxWaitDepth) {
            // The entries being made ready are a chain of waits, and this one lengthens it past the limit: refused
            // at its head, which no order can make shallower, before the recursion runs any deeper.
            List<String> chain = new ArrayList<>(resolving);
            chain.add(name);
            refusedHead = chain.getFirst();
            throw tooDeep(chain);
        }
        resolving.add(name);
        waits.put(name, new LinkedHashSet<>());
        Top resolved;
        try {
            resolved = readiness.resolve(name, declaration);
            measure(name);
        } catch (RuntimeException e) {
            if (refusedHead != null && !refusedHead.equals(name)) {
                throw e; // on its way to the head of a chain refused as too deep, which reports it
            }
            refusedHead = null;
            resolved = readiness.failed(name, declaration, e);
            depths.put(name, 0);
        } finally {
            resolving.remove(name);
            waits.remove(name);
            if (name.equals(refusedHead)) {
                refusedHead = null;
            }
        }
        entries.put(name, resolved);
        return resolved;
    }

    /**
     * Answered over the declarations not yet ready by their parameter lists and alias targets as written, so asking
     * waits on nothing: an open record applied inside its own declaration, or inside one its declaration waits on,
     * would otherwise wait on itself.
     */
    @Override
    public boolean opens(String name) {
        Set<String> seen = new HashSet<>();
        for (String at = name; at != null && seen.add(at); ) {
            Top ready = entries.get(at);
            if (ready != null) {
                if (!(ready instanceof Alias alias)) {
                    return OpenEntry.isOpen(ready);
                }
                at = alias.target().name();
                continue;
            }
            SchemaMap.Declaration declaration = declarations.get(at);
            if (declaration == null) {
                return false;
            }
            if (!SchemaDesugarer.typeParams(declaration.typeDef()).isEmpty()) {
                return true;
            }
            at = declaration.typeDef() instanceof TypeExprDef reference && reference.ref() instanceof NameRef target
                    ? target.name() : null;
        }
        return false;
    }

    /** {@link #read(String, boolean, Function)} in object-binding mode. */
    <T> T read(String type, Function<TsonCompiledSchema, T> read) {
        return read(type, false, read);
    }

    /**
     * Reads a value against {@code type}, an import or an entry of this document already ready, through the readers
     * of a checkpoint, in tree mode where {@code tree} is true. A read is atomic within its checkpoint: one that
     * reaches what its readers lack -- a declaration by position, a tag or an annotation's name ({@link #typeNamed}),
     * a pin a declaration may state ({@link #pinsUnmatched}) -- is abandoned, that declaration made ready as a wait
     * of the entry being made ready, and the read run again over readers that hold it.
     */
    <T> T read(String type, boolean tree, Function<TsonCompiledSchema, T> read) {
        TsonCompiledSchema outer = reading;
        try {
            while (true) {
                TsonCompiledSchema compiled = readersFor(type, tree);
                reading = compiled;
                try {
                    return read.apply(compiled);
                } catch (PendingTypeReader.NotReady wait) {
                    if (compiled.schema().entries().containsKey(wait.entry())) {
                        throw new IllegalStateException("'" + wait.entry() + "' is compiled at this checkpoint, "
                                + "and a read still reports it pending", wait);
                    }
                    definition(wait.entry());
                    bound = null;
                    this.tree = null;
                }
            }
        } finally {
            reading = outer;
        }
    }

    /**
     * A read is about to decide by {@code name}, written as a tag or an annotation's name: where it is a declaration
     * of this document its readers do not hold, the read stops, to run again once it is ready.
     */
    void typeNamed(String name) {
        if (reading != null && declarations.containsKey(name) && !reading.schema().entries().containsKey(name)) {
            throw PendingTypeReader.notReady(name);
        }
    }

    /**
     * A read met a value of the family {@code base} that no member its readers hold pins as the value does: it waits
     * on each declaration its readers do not hold that may be that member ({@link FamilyJoiners}), and stops, to run
     * again once they are ready.
     */
    void pinsUnmatched(String base, Set<String> selectors, BiPredicate<String, String> samePin) {
        if (reading == null) {
            return;
        }
        TsonCompiledSchema held = reading;
        List<String> joiners = FamilyJoiners.of(base, selectors, samePin, declarations, entries,
                name -> !held.schema().entries().containsKey(name));
        if (!joiners.isEmpty()) {
            joiners.forEach(this::definition);
            throw PendingTypeReader.notReady(joiners.getLast());
        }
    }

    /**
     * A read through {@link #read} reached {@code declaration}: where it is an entry of this document already ready,
     * the entry being made ready waits on it as on one the read had to make ready, so a wait is counted whichever
     * order the two were reached in. One not yet ready stops the read instead ({@link PendingTypeReader.NotReady}).
     */
    void reached(SchemaLocation declaration) {
        String pointer = declaration.pointer();
        if (identity.equals(declaration.schemaId()) && pointer.lastIndexOf('/') == 0
                && entries.containsKey(pointer.substring(1))) {
            waited(pointer.substring(1));
        }
    }

    /**
     * Drops the compiled readers, so the next read compiles the entries as they stand: called once every
     * declaration is ready, after which a resolution rewrites entries in place (materialisation, a condemned
     * template's placeholder) rather than adding them one by one.
     */
    void settled() {
        bound = null;
        tree = null;
    }

    /** The checkpoint's readers, compiled where none are held or those held lack {@code type}. */
    private TsonCompiledSchema readersFor(String type, boolean inTree) {
        TsonCompiledSchema compiled = inTree ? tree : bound;
        if (compiled == null || !compiled.schema().entries().containsKey(type)) {
            Set<String> pending = new LinkedHashSet<>(declarations.keySet());
            pending.removeAll(entries.keySet());
            compiled = readers.compile(pending, inTree);
            if (inTree) {
                tree = compiled;
            } else {
                bound = compiled;
            }
        }
        return compiled;
    }

    /** Records that the entry being made ready waits on {@code name}, one of this document's declarations. */
    private void waited(String name) {
        String waiting = current();
        if (waiting != null && !waiting.equals(name) && declarations.containsKey(name)) {
            waits.get(waiting).add(name);
        }
    }

    /** {@code name}'s wait depth, from the depths of the entries it waited on, refused past the limit. */
    private void measure(String name) {
        int depth = 0;
        String through = null;
        for (String waitedOn : waits.get(name)) {
            int candidate = depths.getOrDefault(waitedOn, 0) + 1;
            if (candidate > depth) {
                depth = candidate;
                through = waitedOn;
            }
        }
        if (depth > maxWaitDepth) {
            List<String> chain = new ArrayList<>();
            chain.add(name);
            for (String next = through; next != null; next = deepest.get(next)) {
                chain.add(next);
            }
            throw tooDeep(chain);
        }
        depths.put(name, depth);
        if (through != null) {
            deepest.put(name, through);
        }
    }

    /** {@code name} reached again while it is being made ready: the cycle from its first wait back to it. */
    private static SchemaValidationException cycle(String name, SequencedSet<String> resolving) {
        List<String> chain = new ArrayList<>();
        boolean on = false;
        for (String entry : resolving) {
            on |= entry.equals(name);
            if (on) {
                chain.add(entry);
            }
        }
        chain.add(name);
        return new SchemaValidationException(waits(chain) + ": entries waiting on each other are never ready -- an "
                + "entry's declaration is read against its head, the types of its annotations, its refinement "
                + "source and its '&' operands, and every type a part of it is read against, each of which must be "
                + "ready first ([TSON-SCHEMA] §3.4.1). A name at a type position orders nothing: type a field by the "
                + "entry instead of reading a value against it");
    }

    /** A chain of waits past the limit, refused at its head. */
    private SchemaRefusalException tooDeep(List<String> chain) {
        List<String> shown = chain.size() <= 6 ? chain
                : List.of(chain.get(0), chain.get(1), chain.get(2), "…", chain.getLast());
        return new SchemaRefusalException(Diagnostic.Code.LIMIT_EXCEEDED, "'" + chain.getFirst() + "' waits on a "
                + "chain of " + (chain.size() - 1) + " entries, each waiting on the next being ready (" + waits(shown)
                + "), more than the wait depth of " + maxWaitDepth + " this processor admits ([TSON-SCHEMA] §11.5)",
                null);
    }

    private static String waits(List<String> chain) {
        StringBuilder text = new StringBuilder();
        for (String entry : chain) {
            if (!text.isEmpty()) {
                text.append(" waits on ");
            }
            text.append(entry.equals("…") ? entry : "'" + entry + "'");
        }
        return text.toString();
    }
}
