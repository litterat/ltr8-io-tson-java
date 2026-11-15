package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.compiler.resolver.Externs;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.TypeRef;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.tree.TsonTuple;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.tson.tree.TsonVoid;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The position an extern types ([TSON-SCHEMA] §5.13): the boundary between the scope that holds a value and the
 * foreign schema's, which the value's body is read in (§7.9).
 *
 * <p>The tag and the annotations in front of the value are the enclosing scope's: they are read here, against this
 * namespace, before the value crosses. The tag selects how the value crosses -- a name of this scope reaching the
 * target, a subtype or a variant of it, judged of the entries by identity -- and a foreign reader reads the rest,
 * untagged, as that entry. Inside, the read is the foreign schema's (its readers resolve every name in its
 * namespace), the cursor, receiver, policy and limits the document's ({@link ScopeCrossing}).
 *
 * <p>Built in tree mode only: in bind mode one bind context's name binder serves this schema and every foreign
 * one, so two schemas' {@code card} would bind to one class, and the read reports the gap ({@link #unbound}).
 */
public final class ExternReader implements TsonTypeReader<Object> {

    /**
     * How a value crosses through one extern: the foreign entry's reader, which reads the value untagged as that
     * entry, the scope it is read in, and whether the entry is abstract with no selectors, where a value must name a
     * subtype of it.
     */
    public record Crossing(TsonTypeReader<?> reader, ForeignScope scope, boolean namesASubtype) {
    }

    private final String name;
    private final SchemaLocation location;
    private final AnnotationTypes annotations;
    private final Crossing own;
    private final Map<String, Crossing> admitted;
    private final String narrower;
    private final String admissible;
    private final ScopeCrossing crossing;
    private final TsonLinkedSchema outer;

    /**
     * @param admitted every name this scope may tag the value with, mapped to how the value crosses
     * @param narrower the admitted names landing on a subtype or a variant of this one's landing, joined for a message
     */
    public ExternReader(String name, ValueReaderContext context, Crossing own, Map<String, Crossing> admitted,
                        String narrower, ScopeCrossing crossing) {
        this.name = name;
        this.location = context.locationOf(name);
        this.annotations = AnnotationTypes.of(context);
        this.own = own;
        this.admitted = Map.copyOf(admitted);
        this.admissible = String.join(" | ", admitted.keySet());
        this.narrower = narrower;
        this.crossing = crossing;
        this.outer = context.linked();
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(location);
        EventSkip.typeRefAhead(ctx);
        List<TsonAnnotation> leading = AnnotationCapture.annotations(ctx, annotations);
        Crossing through = own;
        String outerName = name;
        if (ctx.peek() instanceof TypeRef tag) {
            ctx.next();
            through = admitted.get(tag.name());
            if (through == null) {
                report(ctx, refusal(tag.name()), admissible, tag.name());
                EventSkip.coreValue(ctx);
                return null;
            }
            if (through.namesASubtype()) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "a tag naming '" + tag.name() + "' selects nothing -- "
                        + abstractNamed(tag.name()), narrower.isEmpty() ? "no tag this scope can write" : narrower,
                        "!" + tag.name());
                EventSkip.coreValue(ctx);
                return null;
            }
            outerName = tag.name();
        } else if (own.namesASubtype()) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'" + name + "' takes a value that names its type -- "
                    + abstractNamed(name), narrower.isEmpty() ? "no tag this scope can write" : narrower, "(no tag)");
            EventSkip.coreValue(ctx);
            return null;
        }
        Object read = crossing.read(ctx, through.scope(), through.reader());
        return read instanceof TsonValue value ? named(value, outerName).withAnnotations(leading) : read;
    }

    /** A tag this boundary does not admit: unresolved in this scope, or reaching a type the target does not admit. */
    private String refusal(String tag) {
        String admits = "a tag is admitted where it reaches the target, a subtype of it, or a variant of it ("
                + admissible + ") ([TSON-SCHEMA] §7.9)";
        return outer.schema().entries().containsKey(tag)
                ? "'" + tag + "' is not admissible at a '" + name + "' position, which is " + describe()
                        + ": the tag is read in the enclosing scope, where '" + tag + "' reaches another type, and "
                        + admits
                : "'" + tag + "' names no type of the enclosing scope, and a tag at a '" + name + "' position, which "
                        + "is " + describe() + ", is read there: the foreign schema's own names are not, and " + admits;
    }

    private void report(TsonReadContext ctx, String message, String expected, String actual) {
        boolean unresolved = !outer.schema().entries().containsKey(actual);
        ctx.report(unresolved ? Diagnostic.Code.UNKNOWN_TYPE_REF : Diagnostic.Code.TYPE_MISMATCH, message, expected,
                "!" + actual);
    }

    private String abstractNamed(String written) {
        return "'" + written + "' reaches an abstract record naming no discriminators, which has no direct "
                + "instances, so a value here names a subtype of it, by a name of the enclosing scope, "
                + (narrower.isEmpty() ? "and this scope has none" : "one of (" + narrower + ")")
                + " ([TSON-SCHEMA] §7.2, §7.9)";
    }

    private String describe() {
        return outer.schema().entries().get(name) instanceof Extern extern ? Externs.describe(extern) : "an extern";
    }

    /**
     * A tree value at the boundary named by this scope's name for it, which the outer scope reads back; the names
     * inside stay the foreign schema's.
     */
    private static TsonValue named(TsonValue value, String outerName) {
        Optional<String> as = Optional.of(outerName);
        return switch (value) {
            case TsonRecord n -> new TsonRecord(n.fields(), as, n.annotations());
            case TsonMap n -> new TsonMap(n.entries(), as, n.annotations());
            case TsonArray n -> new TsonArray(n.elements(), as, n.annotations());
            case TsonTuple n -> new TsonTuple(n.elements(), as, n.annotations());
            case TsonAtom n -> new TsonAtom(n.value(), as, n.annotations());
            case TsonVoid n -> new TsonVoid(as, n.annotations());
            default -> value;
        };
    }

    /**
     * The entry {@code written} reaches through an extern of {@code linked}'s namespace, past alias hops on both
     * sides of every boundary; empty where it lands through no attached extern.
     */
    public static Optional<ExternTarget.Landing> landing(String written, TsonLinkedSchema linked) {
        Namespace<Top> entries = linked.schema().entries();
        String terminal = AliasChain.terminal(written, entries::get);
        if (!(entries.get(terminal) instanceof Extern)) {
            return Optional.empty();
        }
        ExternTarget target = linked.externs().get(terminal);
        return target == null ? Optional.empty() : target.landing();
    }

    /** Whether a value at a position typed by {@code definition} must name a subtype: abstract, no selectors. */
    public static boolean namesASubtype(Top definition) {
        return FamilySelectors.extensionOf(definition).filter(RecordExtensionType.ABSTRACT::equals).isPresent()
                && FamilySelectors.namesOf(definition).isEmpty();
    }

    /**
     * The reader an extern compiles to in bind mode: binding a foreign value waits for binding by entry identity, so
     * the read reports the gap and skips the value, as {@link ErrorReader} does.
     */
    public static TsonTypeReader<Object> unbound(String name, Extern extern, SchemaLocation location) {
        return ctx -> {
            TsonReadContext at = ctx.underDeclaration(location);
            at.report(Diagnostic.Code.NOT_IMPLEMENTED, "'" + name + "' is " + Externs.describe(extern) + ", and "
                    + "binding a value read through an extern is not implemented yet: one bind context names the "
                    + "classes of this schema and of every foreign one, so two schemas' names would bind to one "
                    + "class until binding is by entry identity; read it as a tree ([TSON-SCHEMA] §7.9)",
                    "a type this library can bind", "");
            EventSkip.dataValue(at);
            return null;
        };
    }
}
