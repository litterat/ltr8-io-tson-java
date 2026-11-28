package io.ltr8.tson.encoding;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.UnboundNameException;
import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Top;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which host class each entry of a linked schema binds to under one {@link DataBindContext}: the answer both
 * encodings' bind readers are built from, so a read and a caller asking ahead of any read get the same one.
 *
 * <p>A binding is loaded ({@link #load}) when a schema is compiled for bind mode, and a compiled schema hands back the
 * one its compile loaded ({@code TsonCompiledSchema.binding()}, {@code JsonCompiledSchema.binding()}): a caller holding
 * a compiled schema takes that one rather than loading another over the same context.
 *
 * <p>Loading asks the binder for every entry the schema holds, because a read may ask about any of them: a document's
 * root and every tag it writes name an entry, not only the ones a reader was built for. Every answer is stored then;
 * afterwards a binding never consults the context, is immutable, and may be shared and queried from any thread.
 */
public final class TsonSchemaBinding {

    private final TsonLinkedSchema schema;
    private final Map<String, BoundEntry> entries;

    private TsonSchemaBinding(TsonLinkedSchema schema, Map<String, BoundEntry> entries) {
        this.schema = schema;
        this.entries = entries;
    }

    /**
     * Loads the binding of {@code schema}'s entries under {@code context}, on the calling thread: each entry's
     * binding candidates ({@link TsonLinkedSchema#bindingCandidates}) asked of the context's binder in order, each
     * candidate once, and the class the first bound one names described.
     *
     * @param schema  a linked schema
     * @param context the context a bind read of {@code schema} binds with
     * @return the loaded binding, holding an answer for every entry of {@code schema}
     */
    public static TsonSchemaBinding load(TsonLinkedSchema schema, DataBindContext context) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(context, "context");
        Map<EntryKey, Descriptor> asked = new HashMap<>();
        Map<String, BoundEntry> entries = new HashMap<>();
        for (String name : schema.schema().entries().keySet()) {
            answer(schema, name, context, asked).ifPresent(entry -> entries.put(name, entry));
        }
        return new TsonSchemaBinding(schema, Map.copyOf(entries));
    }

    /** The linked schema this binding answers for. */
    public TsonLinkedSchema schema() {
        return schema;
    }

    /**
     * What the entry {@code writtenName} binds to.
     *
     * @param writtenName the name of an entry of {@link #schema()}, as a schema or a document writes it: an alias or
     *                    an extern is answered for the entry it reaches
     * @throws IllegalArgumentException where {@link #schema()} holds no entry of that name, or one whose alias chain
     *                                  reaches none
     */
    public BoundEntry entry(String writtenName) {
        BoundEntry entry = entries.get(writtenName);
        if (entry == null) {
            throw new IllegalArgumentException("'" + writtenName + "' is no entry of " + schema.identity()
                    + " that reaches a type");
        }
        return entry;
    }

    /**
     * {@link #entry}, or empty where {@link #schema()} holds no entry {@code writtenName} that reaches a type -- for a
     * caller asking about a name a document wrote, which may be none.
     */
    public Optional<BoundEntry> find(String writtenName) {
        return Optional.ofNullable(entries.get(writtenName));
    }

    /**
     * {@code name}'s answer, or empty where its alias chain reaches no entry: a namespace still being built, which a
     * resolution's checkpoint compiles.
     */
    private static Optional<BoundEntry> answer(TsonLinkedSchema schema, String name, DataBindContext context,
                                               Map<EntryKey, Descriptor> asked) {
        Optional<ExternTarget.Landing> landing = schema.landing(name);
        TsonLinkedSchema holder = landing.map(ExternTarget.Landing::in).orElse(schema);
        String entry = landing.map(end -> holder.terminal(end.name())).orElseGet(() -> schema.terminal(name));
        Top definition = holder.schema().entries().get(entry);
        if (definition == null) {
            return Optional.empty();
        }
        return Optional.of(new BoundEntry(new EntryKey(holder.declaringIdentity(entry), entry), definition,
                descriptor(schema.bindingCandidates(name), context, asked),
                AtomParsers.forEntry(entry, definition, holder.enumForm(entry))));
    }

    private static Descriptor descriptor(List<EntryKey> candidates, DataBindContext context,
                                         Map<EntryKey, Descriptor> asked) {
        DataBindException refusal = null;
        for (EntryKey candidate : candidates) {
            Descriptor answer = asked.computeIfAbsent(candidate, key -> ask(key, context));
            if (!(answer instanceof Descriptor.Unbound unbound)) {
                return answer;
            }
            refusal = refusal == null ? unbound.refusal() : refusal;
        }
        return new Descriptor.Unbound(new ArrayList<>(candidates), refusal);
    }

    private static Descriptor ask(EntryKey candidate, DataBindContext context) {
        try {
            return new Descriptor.Bound(context.getDescriptor(candidate.declaringSchema(), candidate.name()),
                    candidate);
        } catch (UnboundNameException unbound) {
            return new Descriptor.Unbound(List.of(candidate), unbound);
        } catch (DataBindException unanalysable) {
            return new Descriptor.Unanalysable(candidate, unanalysable);
        } catch (RuntimeException broken) {
            // A binder or a class's analysis failing outright is still this deployment's class, never a gap of the
            // library's: it is answered as a class the context cannot describe.
            return new Descriptor.Unanalysable(candidate, new DataBindException("'" + candidate.name()
                    + "' could not be bound: " + broken, broken));
        }
    }
}
