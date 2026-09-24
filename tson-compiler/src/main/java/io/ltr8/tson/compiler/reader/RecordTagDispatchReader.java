package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.diagnostics.RecordExtensionDiagnostics;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.stream.EventSkip;

import java.util.Optional;
import java.util.Set;

/**
 * An ABSTRACT record position ([TSON-SCHEMA] §5.2): the record has no direct instances, so a value here is a
 * value of one of its subtypes and the {@code !name} annotation is the only thing that can say which. The
 * failure lands before the record's shape is consulted, which is the same refusal [TSON-JSON] §6.1.5 gives a
 * missing {@code $type}.
 *
 * <p><b>Named for what it does rather than for the member it serves.</b> {@code RecordAbstractReader} is
 * taken, and by a different meaning: {@code *AbstractReader} is this package's name for the shared base of a
 * kind's two mode readers, four classes deep. {@code *DispatchReader} is the established name for a reader
 * that resolves another and hands the value on ({@link NamedDispatchReader}), which is exactly this.
 *
 * <p><b>One reader for both modes</b>, on {@link ChoiceReader}'s reasoning: dispatch reads the type-ref
 * without consuming it, so the subtype's own reader takes the whole data-value and does with it whatever that
 * mode does everywhere else. There is nothing here for a mode to differ about.
 *
 * <p>{@link NamedDispatchReader} is deliberately <em>not</em> reused, close as the shape is: its verdicts are
 * a choice's, and [TSON-JSON] §9.4 binds a family's to the ones the JSON stack gives -- which is what {@link
 * RecordExtensionDiagnostics} exists to hold. Sharing the dispatch would have meant sharing the wording with §8.2's
 * rule, which is a different rule.
 */
final class RecordTagDispatchReader implements TsonTypeReader<Object>, Subsumption.Applied {

    /** How this encoding spells a tag, for the {@code actual} of a rule whose message may not name it. */
    private static final String TAG = "!type annotation";

    /**
     * Every written name that means the base -- its own, and any alias whose chain ends at it. Naming it is
     * refused with its own rule, the remedy differing (§8.1), and an alias names it exactly as much (§7.2's
     * "after reference flattening of both").
     */
    private final Set<String> selfNames;

    /** The same for each subtype: what a tag may spell to select it, the entry's own name among them. */
    private final Set<String> subtypes;

    private final TsonTypeReaderResolver readerFor;
    private final RecordExtensionDiagnostics extension;

    RecordTagDispatchReader(Set<String> selfNames, String displayName, Set<String> subtypes,
                             TsonTypeReaderResolver readerFor) {
        this.selfNames = Set.copyOf(selfNames);
        this.subtypes = subtypes;
        this.readerFor = readerFor;
        this.extension = new RecordExtensionDiagnostics(displayName, String.join(" | ", subtypes));
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> tag = EventSkip.typeRefAhead(ctx);
        if (tag.isEmpty()) {
            ctx.report(extension.tagRequired(TAG));
            EventSkip.dataValue(ctx);
            return null;
        }
        if (!subtypes.contains(tag.get())) {
            // The base itself is not admissible, which is the whole of what ABSTRACT means -- so a tag naming
            // it is refused here where a concrete position takes one as a redundant restatement (§8.1). It
            // gets its own wording because the remedy differs: the tag is not wrong about the position.
            ctx.report(selfNames.contains(tag.get()) ? extension.tagNamesTheBase(TAG)
                    : extension.notASubtype(TAG, tag.get()));
            EventSkip.dataValue(ctx);
            return null;
        }
        return readerFor.resolve(tag.get()).read(ctx);
    }
}
