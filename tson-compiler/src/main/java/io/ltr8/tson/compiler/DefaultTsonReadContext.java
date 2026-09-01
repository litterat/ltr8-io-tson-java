package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.atom.IdentifierParser;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one backing implementation of {@link TsonReadContext}. It holds no policy of its own -- {@link #report}
 * enriches a problem with the path and positions tracked here and hands it to the read's {@link
 * TsonDiagnosticsReceiver}, which decides what happens next. Package-private: a caller only ever reaches this
 * through {@link TsonReadContext}'s own static factories, never by name -- see that interface's own Javadoc for
 * why it's an interface at all.
 */
final class DefaultTsonReadContext implements TsonReadContext {

    /**
     * Every scoped copy of a single read (see {@link #field}/{@link #index}/{@link
     * #schemaField}/{@link #withPosition}) shares one of these -- the real event source, its
     * own live cursor position (mutated by {@link #peek}/{@link #next} regardless of which copy made
     * the call, since there is only ever one real cursor per read), the receiver every copy reports
     * through, and the running count of what they have reported.
     */
    private static final class Cursor {
        final TsonEventSource events;
        final TsonDiagnosticsReceiver receiver;

        /**
         * [TSON-DATA] §8.2's restricted-script rule over the names this document carries, defaulting to Highly
         * Restrictive as §8.2 says it SHOULD. Per read rather than per context, so every derived context
         * checks against the one a caller named.
         */
        final TsonUnicodePolicy identifierPolicy;

        /**
         * Where the cursor is: the position of the last event {@link #peek()} or {@link #next()} returned,
         * {@code null} before the first. <b>Held as the event's own {@link SourcePosition}, not wrapped</b>
         * -- {@link #position()} wraps it when asked, which is once per diagnostic, where this is assigned
         * on every pull. An {@link Optional} per pull was 2.6 KB of the ~23 KB a read allocated, for a value
         * the event already carries and almost no read ever looks at.
         */
        SourcePosition position;

        int reported;

        /**
         * Events consumed by a {@link #lookingAhead} pass and rewound, replayed ahead of {@code events}
         * until they run out. Empty for the whole of an ordinary read -- the one thing that fills it is a
         * caller that had to look further than {@link #peek()} reaches to know what to do next.
         */
        final Deque<TsonEvent> rewound = new ArrayDeque<>();

        /** Where {@link #next()} records what it consumes while a lookahead is running, else {@code null}. */
        List<TsonEvent> recording;

        Cursor(TsonEventSource events, TsonDiagnosticsReceiver receiver, TsonUnicodePolicy identifierPolicy) {
            this.events = events;
            this.receiver = receiver;
            this.identifierPolicy = identifierPolicy;
        }
    }

    /**
     * One step of the descent, linked to the step before it -- {@code /lines}, {@code /2}, {@code /sku} as
     * three nodes rather than three progressively longer strings.
     *
     * <p><b>Both pointers a diagnostic carries are built from this, and only when one is built.</b> A read
     * that reports nothing -- which is every read of a valid document, the overwhelming majority -- pays a
     * node per step and no string at all, where concatenating eagerly cost a {@code StringBuilder}, a
     * {@code String} and its array at every field of every value, then threw them away. The structure is
     * what matters beyond this implementation: any port descends the same way and would inherit the same
     * waste from the same shape.
     *
     * <p>{@code schemaToo} is what keeps the two pointers apart. Every schema step is a data step, but not
     * the reverse: an array index moves through the document without moving through the schema, whose
     * element type is declared once for the whole array. So one chain carries both, and the schema pointer
     * renders the marked subset.
     *
     * @param name  the field name, or {@code null} for an array index
     * @param index the array index, meaningful only when {@code name} is {@code null}
     */
    private record PathStep(PathStep parent, String name, int index, boolean schemaToo) {
    }

    private final Cursor cursor;

    /** The step this context sits at; {@code null} at the root, whose path is {@code ""}. */
    private final PathStep tail;

    /**
     * The schema pointer's own root -- the declaration the descent entered through, e.g. {@code "/person"}
     * -- or {@code null} where no schema is in scope. Held apart from {@link #tail} because a re-anchoring
     * record replaces the identity and the line while the pointer keeps growing ({@link SchemaLocation}).
     */
    private final String schemaRoot;
    private final String schemaId;
    private final Optional<SourcePosition> schemaPosition;

    private final Optional<SourcePosition> positionOverride;

    private DefaultTsonReadContext(Cursor cursor, PathStep tail, String schemaRoot, String schemaId,
                                    Optional<SourcePosition> schemaPosition,
                                    Optional<SourcePosition> positionOverride) {
        this.cursor = cursor;
        this.tail = tail;
        this.schemaRoot = schemaRoot;
        this.schemaId = schemaId;
        this.schemaPosition = schemaPosition;
        this.positionOverride = positionOverride;
    }

    static TsonReadContext of(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
        return of(events, receiver, TsonUnicodePolicy.highlyRestrictive());
    }

    static TsonReadContext of(TsonEventSource events, TsonDiagnosticsReceiver receiver,
                              TsonUnicodePolicy identifierPolicy) {
        return new DefaultTsonReadContext(new Cursor(events, receiver, identifierPolicy), null, null, null,
                Optional.empty(), Optional.empty());
    }

    private DefaultTsonReadContext stepping(PathStep step) {
        return new DefaultTsonReadContext(cursor, step, schemaRoot, schemaId, schemaPosition, positionOverride);
    }

    @Override
    public TsonEvent peek() {
        TsonEvent e = cursor.rewound.isEmpty() ? cursor.events.peek() : cursor.rewound.peekFirst();
        cursor.position = e.position();
        return e;
    }

    @Override
    public TsonEvent next() {
        boolean fresh = cursor.rewound.isEmpty();
        TsonEvent e = fresh ? cursor.events.next() : cursor.rewound.removeFirst();
        cursor.position = e.position();
        if (cursor.recording != null) {
            cursor.recording.add(e);
        }
        if (fresh) {
            checkNameHygiene(e);
        }
        return e;
    }

    /**
     * [TSON-DATA] §8.2's <b>restricted-character and restricted-script</b> rules over the two names a Class 1
     * document carries -- a type-ref name and an annotation name, the positions §7.4 marks {@code identifier}.
     *
     * <p>Both default on, which §8.2 requires: the identifier profile MUST apply, and a name's scripts SHOULD
     * be judged at Highly Restrictive over the whole name. They are one report shape because they are one outcome -- the
     * document is refused, and which table said so is the message's business.
     *
     * <p><b>Here rather than in {@code TsonDataStream}, because a refusal needs a receiver.</b> §8.2 makes a
     * restricted-character failure a policy refusal: the document is not invalid, it is refused by this processor
     * under a policy reading data the UCD does not freeze, and it MUST NOT be reported in any of §8.1's four
     * categories. The stream throws {@link TsonParseException} and holds no receiver, so a check there can
     * only say "invalid", which is the one thing this is not. The grammar stays there, where a failure
     * really is a parse error ({@code IdentifierParser.validate}), and the policy is applied here.
     *
     * <p><b>Only on a freshly pulled event.</b> {@link TsonReadContext#lookingAhead} rewinds what it
     * consumed and a reader replays it, so checking every event would report a refused name once per
     * lookahead that crossed it. {@code NameHygieneTest} pins exactly-once across every shape an annotation
     * takes, the nested and map-key ones included, because that is what would regress silently.
     *
     * <p><b>Not in {@code TokenPolicyEventSource}</b>, which is where the token surface's own policy runs and
     * gets exactly-once for free by sitting upstream of the rewind. That decorator skips itself entirely at
     * the default policy -- {@code unrestricted()}, which is every ordinary read -- so a rule §8.2
     * defaults <em>on</em> cannot live behind it without making the wrapper unconditional and putting a
     * switch per token back into the steady-state cost of a read that has no policy at all.
     */
    private void checkNameHygiene(TsonEvent event) {
        String name = switch (event) {
            case io.ltr8.tson.compiler.stream.TypeRef typeRef -> typeRef.name();
            case io.ltr8.tson.compiler.stream.AnnotationStart annotation -> annotation.name();
            default -> null;
        };
        if (name == null) {
            return;
        }
        // The restricted-character rule is gated on the level, per §8.2: Unrestricted "drops the profile
        // too", taking that rule with it. Script mixing gates itself inside violation().
        if (cursor.identifierPolicy.appliesIdentifierProfile()) {
            IdentifierParser.hygiene(name).ifPresent(violation ->
                    refuse(name, violation, Diagnostic.Code.RESTRICTED_CHARACTER));
        }
        cursor.identifierPolicy.violation(name).ifPresent(violation ->
                refuse(name, violation, Diagnostic.Code.RESTRICTED_SCRIPT));
    }

    /**
     * One shape for both name-hygiene rules: refused under this read's <em>name</em> policy, never
     * invalid. The rule is the code -- a character outside the identifier profile and a script the policy
     * does not admit want different fixes, so they are two codes rather than one code and a discriminator
     * beside it.
     */
    private void refuse(String name, String violation, Diagnostic.Code code) {
        report(code, "the name " + violation, "a name this processor will accept", "'" + name + "'");
    }

    /**
     * {@link TsonReadContext#lookingAhead}'s implementation, which carries the contract and the reasoning.
     * The cast cannot fail for a context this library made: this class is the interface's one implementation
     * (see the class note), and every context reaches a reader through {@link TsonReadContext#of}.
     */
    static <T> T lookingAhead(TsonReadContext ctx, Function<TsonReadContext, T> lookahead) {
        Cursor cursor = ((DefaultTsonReadContext) ctx).cursor;
        List<TsonEvent> consumed = new ArrayList<>();
        List<TsonEvent> outer = cursor.recording;
        cursor.recording = consumed;
        try {
            return lookahead.apply(ctx);
        } finally {
            cursor.recording = outer;
            // Front of the queue, in the order they were read: these events precede whatever is still
            // unread, and a nested lookahead's rewind must land ahead of the enclosing one's.
            // The rewound queue is the single record of what has been read and put back: an enclosing
            // lookahead does not also take these, or it would push back a second copy of events this one
            // has already returned. It records them again itself if it reads through them.
            for (int i = consumed.size() - 1; i >= 0; i--) {
                cursor.rewound.addFirst(consumed.get(i));
            }
        }
    }

    @Override
    public Optional<SourcePosition> position() {
        return positionOverride.isPresent() ? positionOverride : Optional.ofNullable(cursor.position);
    }

    /** Built on demand, from {@link #schemaRoot} plus the marked steps -- see {@link PathStep}. */
    @Override
    public Optional<SchemaLocation> schemaLocation() {
        if (schemaRoot == null) {
            return Optional.empty();
        }
        return Optional.of(new SchemaLocation(schemaId, render(true), schemaPosition));
    }

    /** Built on demand, from every step -- see {@link PathStep}. */
    @Override
    public String path() {
        return render(false);
    }

    /**
     * The RFC 6901 pointer {@link #tail}'s chain spells: every step for the data path, the {@code schemaToo}
     * ones for the schema pointer. Rendered nowhere else, and reached only by {@link #report} and a caller
     * that asks.
     */
    private String render(boolean schemaOnly) {
        StringBuilder out = new StringBuilder();
        if (schemaOnly) {
            out.append(schemaRoot);
        }
        append(out, tail, schemaOnly);
        return out.toString();
    }

    private static void append(StringBuilder out, PathStep step, boolean schemaOnly) {
        if (step == null) {
            return;
        }
        append(out, step.parent(), schemaOnly);   // parent first: the chain links backwards, the pointer reads forwards
        if (schemaOnly && !step.schemaToo()) {
            return;
        }
        out.append('/');
        if (step.name() != null) {
            out.append(escape(step.name()));
        } else {
            out.append(step.index());
        }
    }

    @Override
    public TsonReadContext field(String name) {
        return stepping(new PathStep(tail, name, -1, false));
    }

    @Override
    public TsonReadContext index(int i) {
        return stepping(new PathStep(tail, null, i, false));
    }

    @Override
    public TsonReadContext schemaField(String name, Optional<SourcePosition> fieldPosition) {
        // The same one allocation stepping() makes for every other descent; the ternary only chooses which
        // position it carries, and an absent one leaves the enclosing record's exactly as before.
        return new DefaultTsonReadContext(cursor, new PathStep(tail, name, -1, schemaRoot != null),
                schemaRoot, schemaId, fieldPosition.isPresent() ? fieldPosition : schemaPosition,
                positionOverride);
    }

    @Override
    public TsonReadContext inRecord(SchemaLocation declaration) {
        // The pointer survives, the anchor does not: this record declares the field the pointer now ends
        // with, so it is what a reader following the pointer needs opened. Only an outermost record, with no
        // pointer to extend, contributes its own name as the path's root.
        if (schemaRoot == null) {
            return anchoredOn(declaration.pointer(), declaration.schemaId(), declaration.position());
        }
        return anchoredOn(schemaRoot, declaration.schemaId(),
                declaration.position().isPresent() ? declaration.position() : schemaPosition);
    }

    @Override
    public TsonReadContext underDeclaration(SchemaLocation declaration) {
        return schemaRoot != null
                ? this
                : anchoredOn(declaration.pointer(), declaration.schemaId(), declaration.position());
    }

    private TsonReadContext anchoredOn(String root, String id, Optional<SourcePosition> position) {
        return new DefaultTsonReadContext(cursor, tail, root, id, position, positionOverride);
    }

    @Override
    public TsonReadContext withPosition(Optional<SourcePosition> position) {
        return new DefaultTsonReadContext(cursor, tail, schemaRoot, schemaId, schemaPosition, position);
    }

    /** The one place a read builds a {@link Diagnostic} -- every report, refusal included, lands here. */
    @Override
    public void report(Diagnostic.Code code, String message, String expected, String actual,
            Optional<TsonSchemaFetchException.Reason> fetchReason) {
        // All three schema-end components come from the one SchemaLocation the descent accumulated, so they
        // cannot disagree about which document to open. A read with no schema behind it carries none of them:
        // Diagnostic spells a missing identity "" and a missing pointer as an absence, since for a pointer
        // "" is the root.
        Diagnostic diagnostic = new Diagnostic(Optional.of(render(false)),
                schemaRoot == null ? Optional.empty() : Optional.of(render(true)),
                schemaRoot == null ? "" : schemaId, code, message, expected, actual,
                position(), schemaRoot == null ? Optional.empty() : schemaPosition, fetchReason);
        cursor.reported++;
        cursor.receiver.report(diagnostic);
    }

    @Override
    public int reported() {
        return cursor.reported;
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
