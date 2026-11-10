package io.ltr8.tson.compiler;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.compiler.reader.DependentFieldReader;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.ValueEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The dependent fields' values a schema's value entries hold as written ([TSON-SCHEMA] §5.2, {@link
 * TsonSchema#heldValues()}), judged when the schema links against the type each one's sibling names.
 *
 * <p><b>Why at link, and not at the read.</b> A value entry's payload is read by the readers of the schema declaring
 * its type, the layer governing the document, and the type a sibling names is one of the reading schema's namespace
 * -- its own entries and its whole import closure -- which those readers do not hold. So the read holds the value as
 * written ({@code DependentFieldReader}) and hands its events to the linker, as a default or pin is held and judged
 * when the schema links; a held value orders nothing, and the wait order is unchanged.
 *
 * <p><b>The judgement is a read.</b> Once the rest of the link is clean, the linked schema is compiled in tree mode
 * and each held value is read again, through the reader of the type its sibling names, as a schema document's value
 * ({@link TsonReadContext#readsSchemaDocument()}): every refusal that read reports is a schema error at the entry, the
 * slot and the dependent field, and a gap it reports ({@link Diagnostic.Code#NOT_IMPLEMENTED}) is the gap. What the
 * type is follows the read path's rule ({@link ValuesByName#typeNamed}): the sibling's pin, the value it holds, or its
 * default, naming a type a value can be read against. The references the read records by name are judged by {@link
 * ValuesByName} as any other value body's, and a dependent field inside the value is held again and judged in turn.
 *
 * <p>Local value entries only: an imported one was judged when its own schema linked.
 */
final class HeldValues {

    private final TsonLinkedSchema linked;
    private final Map<String, Top> merged;
    private final IdentifierPolicy identifiers;
    private TsonCompiledSchema compiled;

    HeldValues(TsonLinkedSchema linked, Map<String, Top> merged, IdentifierPolicy identifiers) {
        this.linked = linked;
        this.merged = merged;
        this.identifiers = identifiers;
    }

    /**
     * Judges every value local value entry {@code entryName} holds as written, each refusal handed to {@code
     * refused} and each gap to {@code gap} with the entry, and the references the reads record to {@code names}.
     */
    void check(String entryName, ValuesByName names, BiConsumer<String, SchemaValidationException> refused,
               BiConsumer<String, UnsupportedOperationException> gap) {
        if (!(merged.get(entryName) instanceof ValueEntry)) {
            return;
        }
        List<TsonSchema.HeldValue> held = linked.schema().heldValues().get(entryName);
        if (held == null) {
            return;
        }
        Deque<TsonSchema.HeldValue> work = new ArrayDeque<>(held);
        while (!work.isEmpty()) {
            TsonSchema.HeldValue value = work.poll();
            String where = "'" + entryName + "' at " + ValuesByName.pointer(value.slot());
            List<TsonSchema.ValueByName> byName = new ArrayList<>();
            try {
                judge(where, value, byName, work);
            } catch (SchemaValidationException e) {
                refused.accept(entryName, e);
            } catch (UnsupportedOperationException e) {
                gap.accept(entryName, e);
            }
            names.check(entryName, byName, e -> refused.accept(entryName, e), e -> gap.accept(entryName, e));
        }
    }

    /**
     * Reads {@code value} against the type its sibling names, reporting each refusal as a {@link
     * SchemaValidationException} opening with {@code where}; the references the read records go to {@code byName}
     * and the dependent values it holds to {@code work}, each slot from the entry's root.
     */
    private void judge(String where, TsonSchema.HeldValue value, List<TsonSchema.ValueByName> byName,
                       Deque<TsonSchema.HeldValue> work) {
        if (value.named().isEmpty()) {
            return; // the sibling holds no reference: its read, or its absence, is refused at the read
        }
        String naming = where + " is the dependent field '" + value.field() + "', typed by its sibling '"
                + value.sibling() + "'";
        String type = switch (ValuesByName.typeNamed(value.named().get(), merged)) {
            case DependentFieldReader.TypeNamed.Entry entry -> entry.name();
            case DependentFieldReader.TypeNamed.None none -> throw new SchemaValidationException(naming
                    + ", which holds " + none.held() + " -- so its value has no type to be read against");
        };
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        List<String> slot = value.slot();
        TsonReadContext ctx = DefaultTsonReadContext.ofSchemaDocument(new ListEventSource(events(value)), problems,
                identifiers, new TsonReadContext.HeldByName() {

                    @Override
                    public void held(List<String> at, TsonSchema.SlotType slotType,
                                     io.ltr8.tson.schema.meta.TypeRef reference,
                                     io.ltr8.tson.schema.meta.Ref<?> cell) {
                        byName.add(new TsonSchema.ValueByName(under(slot, at), slotType, reference,
                                Optional.ofNullable(cell)));
                    }

                    @Override
                    public void heldAsWritten(TsonSchema.HeldValue nested) {
                        work.add(new TsonSchema.HeldValue(under(slot, nested.slot()), nested.field(), nested.sibling(),
                                nested.named(), nested.written()));
                    }
                }, true);
        compiled().get(type).read(ctx);
        for (Diagnostic problem : problems.diagnostics()) {
            String at = problem.path().filter(path -> !path.isEmpty() && !path.equals("/"))
                    .map(path -> " at " + path).orElse("");
            String message = naming + ", whose value is read against '" + type + "', the type the sibling names, and "
                    + "refused" + at + " -- " + problem.message();
            if (problem.code() == Diagnostic.Code.NOT_IMPLEMENTED) {
                throw new UnsupportedOperationException(message);
            }
            throw new SchemaValidationException(message);
        }
    }

    /** The schema compiled in tree mode, once, on the first value judged. */
    private TsonCompiledSchema compiled() {
        if (compiled == null) {
            compiled = TsonSchemaCompiler.compile(linked, ValueReaderFactoryRegistry.tree());
        }
        return compiled;
    }

    /** The value's events, as the read captured them ({@link TsonReadContext#asWritten}). */
    private static List<TsonEvent> events(TsonSchema.HeldValue value) {
        if (!(value.written() instanceof List<?> written)) {
            throw new IllegalStateException("a held value reached the linker as "
                    + value.written().getClass().getName() + ", and the read holds one as its events");
        }
        List<TsonEvent> events = new ArrayList<>(written.size());
        for (Object event : written) {
            events.add((TsonEvent) event);
        }
        return events;
    }

    /** {@code inner}, a slot from a held value's root, as a slot from the entry's root. */
    private static List<String> under(List<String> outer, List<String> inner) {
        List<String> slot = new ArrayList<>(outer.size() + inner.size());
        slot.addAll(outer);
        slot.addAll(inner);
        return slot;
    }

    /** Whether any of {@code localNames} holds a value as written, so a schema holding none is never compiled here. */
    static boolean anyHeld(TsonSchema schema, Set<String> localNames) {
        return localNames.stream().anyMatch(schema.heldValues()::containsKey);
    }
}
