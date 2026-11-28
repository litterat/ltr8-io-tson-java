package io.ltr8.tson.encoding;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.encoding.plan.BindingTable;
import io.ltr8.tson.encoding.plan.EntryBindings;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.TsonLinkedSchema;

import java.util.List;
import java.util.Optional;

/**
 * Which host class each entry of a linked schema binds to under one {@link DataBindContext}: one
 * {@link TsonTypeBinding} per entry the binder binds, found by its {@link EntryKey}, by a name the schema writes, or
 * by its class. An entry nothing binds is absent. Both encodings' bind readers are built from it, so a read and a
 * caller asking ahead of any read get the same answer.
 *
 * <p>A compiled schema hands back the binding its compile loaded ({@code TsonCompiledSchema.binding()}, {@code
 * JsonCompiledSchema.binding()}); a caller holding one takes that rather than loading another over the same context.
 *
 * <p>Loading asks the binder for every entry the schema reaches, its imports merged in and its externs' landings
 * included, because a document's root and every tag it writes name an entry. Every answer is stored then; afterwards
 * a binding never consults the context, is immutable, and may be shared and queried from any thread.
 */
public final class TsonSchemaBinding implements EntryBindings {

    private final BindingTable table;

    private TsonSchemaBinding(BindingTable table) {
        this.table = table;
    }

    /**
     * Loads the binding of {@code schema}'s entries under {@code context}, on the calling thread: each entry's
     * binding candidates ({@link TsonLinkedSchema#bindingCandidates}) asked of the context's binder in order, each
     * candidate once, and the class the first bound one names described. A parametric record is not asked: nothing
     * reads one bare, and its applications are entries of their own.
     *
     * @param schema  a linked schema
     * @param context the context a bind read of {@code schema} binds with
     * @throws io.ltr8.tson.base.BindMismatchException where the binder names a class {@code context} cannot describe,
     *                                                 or fails when asked; the message names the entry, the class,
     *                                                 why, and the fix
     */
    public static TsonSchemaBinding load(TsonLinkedSchema schema, DataBindContext context) {
        return new TsonSchemaBinding(BindingTable.strict(schema, context));
    }

    /** The linked schema this binding answers for. */
    public TsonLinkedSchema schema() {
        return table.schema();
    }

    /** The context this binding was loaded under, which a read or a write through it binds with. */
    public DataBindContext context() {
        return table.context();
    }

    /** The binding of entry {@code key}; empty where nothing binds it, or {@link #schema()} reaches no such entry. */
    @Override
    public Optional<TsonTypeBinding> get(EntryKey key) {
        return table.get(key);
    }

    /**
     * The binding of the entry {@code writtenName} denotes in {@link #schema()} past its alias hops and its extern
     * ({@link TsonLinkedSchema#entryKey}); empty where nothing binds it, or the name is no entry.
     */
    @Override
    public Optional<TsonTypeBinding> get(String writtenName) {
        return table.get(writtenName);
    }

    /**
     * The binding of entry {@code key}.
     *
     * @throws io.ltr8.tson.base.MissingBindingException where nothing binds it: the names the binder was asked and
     *                                                   its own account of what it does bind
     * @throws IllegalArgumentException                  where {@link #schema()} reaches no entry {@code key}
     */
    @Override
    public TsonTypeBinding require(EntryKey key) {
        return table.require(key);
    }

    /**
     * The binding of the entry a value of {@code type} is: empty where no entry binds it, and the one entry where
     * every entry binding it is bound under one name -- an entry composed over a constructor and bound as it is that
     * constructor's ({@link TsonTypeBinding#boundAs}).
     *
     * @throws io.ltr8.tson.base.BindMismatchException where {@code type} is bound under several names, naming each;
     *                                                 {@link #bindingsOf} lists them
     */
    public Optional<TsonTypeBinding> get(Class<?> type) {
        return table.get(type);
    }

    /**
     * Every entry bound to {@code type}, in {@link #schema()}'s declaration order: for a caller that knows which of
     * them a position admits.
     */
    public List<TsonTypeBinding> bindingsOf(Class<?> type) {
        return table.bindingsOf(type);
    }
}
