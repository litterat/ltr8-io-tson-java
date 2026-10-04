package io.ltr8.tson.compiler.reader;

import java.util.List;
import java.util.ArrayList;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics.Handed;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;


/**
 * Object-binding mode's own {@code tuple} reader -- reads a tuple's own array-shaped value into a
 * real, bound Java object via {@code descriptor}, a {@code tson-bind} {@link DataClassTuple} already
 * resolved for this tuple's own target Java type (same division of responsibility as {@link
 * RecordBindReader}'s {@code DataClassRecord}).
 *
 * <p>Unlike {@link DataClassTuple}'s own read-side use (extracting values back out of an existing
 * tuple, one {@link io.ltr8.bind.DataClassElement#accessor()} at a time), building one is a single
 * call: {@code descriptor.constructor().invoke(values)}, the same all-at-once shape {@link
 * RecordBindReader} builds a record through -- {@link DataClassTuple} has no {@code put()}/iterator
 * at all (see its own Javadoc: a tuple is always built whole, never filled in one slot at a time).
 * As with {@link ArrayBindReader}/{@link MapBindReader}, there's no per-position narrowing here --
 * each position's own binding already happened recursively, inside whatever reader {@code resolver}
 * produced for its type.
 *
 * <p>Everything else -- resolving each position's own reader, confirming a tuple shape, arity
 * checking, absent-position handling -- lives on {@link TupleAbstractReader}.
 */
final class TupleBindReader extends TupleAbstractReader<Object> {

    /** The tuple's schema shape, kept so a record field can build this tuple again for its own class. */
    final TupleBody body;

    /** The class this tuple builds, or {@code null} where nothing is bound to its name ({@link #unbound}). */
    private final DataClassTuple descriptor;

    /** Why no class is bound, raised on read; {@code null} where {@link #descriptor} is set. */
    private final MissingBindingException missing;

    public TupleBindReader(String name, String displayName, TupleBody body, DataClassTuple descriptor,
                           TsonTypeReaderResolver resolver, SchemaLocation schemaLocation) {
        this(name, displayName, body, descriptor, resolver, schemaLocation, AnnotationTypes.DISCARDED);
    }

    public TupleBindReader(String name, String displayName, TupleBody body, DataClassTuple descriptor,
                           TsonTypeReaderResolver resolver,
                           SchemaLocation schemaLocation, AnnotationTypes annotationTypes) {
        this(name, displayName, body, descriptor, null, resolver, schemaLocation, annotationTypes);
    }

    private TupleBindReader(String name, String displayName, TupleBody body, DataClassTuple descriptor,
                            MissingBindingException missing, TsonTypeReaderResolver resolver,
                            SchemaLocation schemaLocation, AnnotationTypes annotationTypes) {
        super(name, displayName, body, resolver, schemaLocation,
                position -> descriptor != null && position < descriptor.elements().length
                        ? descriptor.elements()[position].dataClass()
                        : null,
                annotationTypes);
        this.body = body;
        this.descriptor = descriptor;
        this.missing = missing;
    }

    /**
     * A tuple nothing binds a class to by name -- most often one written inline at a record field, whose entry
     * has a minted name no author would bind. A tuple has no natural Java form to fall back on, as an array has
     * its {@code List}, so this reader builds nothing: a record field rebuilds it against its own component's
     * tuple class ({@code RecordBindReader.rebindContainerIfNeeded}), and read directly it raises {@code
     * missing}, which is what {@link ErrorReader} would have raised for the entry.
     */
    static TupleBindReader unbound(String name, String displayName, TupleBody body, MissingBindingException missing,
                                   TsonTypeReaderResolver resolver, SchemaLocation schemaLocation,
                                   AnnotationTypes annotationTypes) {
        return new TupleBindReader(name, displayName, body, null, missing, resolver, schemaLocation,
                annotationTypes);
    }

    /**
     * Where {@code descriptor} cannot build {@code body}'s values, before any document: an arity of its own, or a
     * voidable position bound to a primitive component, which has no {@code null} for a void value to arrive as.
     * Empty where they agree.
     */
    static List<String> disagreements(TupleBody body, DataClassTuple descriptor) {
        List<String> mismatches = new ArrayList<>();
        if (body.elements().size() != descriptor.elements().length) {
            mismatches.add("it has " + body.elements().size() + " positions, and "
                    + descriptor.typeClass().getSimpleName() + " has " + descriptor.elements().length);
            return mismatches;
        }
        for (int i = 0; i < body.elements().size(); i++) {
            Class<?> element = descriptor.elements()[i].dataClass().typeClass();
            if (body.elements().get(i).voidable() && element.isPrimitive()) {
                mismatches.add("position " + i + " is voidable, and " + element.getName()
                        + " has no null to hold a void value");
            }
        }
        return mismatches;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        if (missing != null) {
            throw missing;
        }
        ctx = ctx.underDeclaration(schemaLocation);
        if (!expectTupleStart(ctx)) {
            return null;
        }
        int mark = ConstructionGuard.mark(ctx);
        Object[] decoded = decode(ctx);
        if (ConstructionGuard.abandoned(ctx, mark)) {
            return null; // see ConstructionGuard: bind mode never builds out of a document already reported
        }
        try {
            return descriptor.constructor().invoke(decoded);
        } catch (Throwable t) {
            ctx.report(BindingDiagnostics.rejectedUnderSchema(descriptor.typeClass(), Handed.POSITIONS, t));
            return null;
        }
    }

    /** Validates both halves of what {@link #TupleBindReader} needs before ever constructing one -- {@code typeDefinition} is tuple-shaped, and {@code context} resolves {@code name} to a real, tuple-shaped {@link DataClassTuple} -- matching {@link RecordBindReader.Factory}'s own reasoning. */
    public static final class Factory implements ValueReaderFactory {

        private final DataBindContext context;

        public Factory(DataBindContext context) {
            this.context = context;
        }

        @Override
        public TsonTypeReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderContext context) {
            TsonTypeReaderResolver resolver = context.readers();
            if (!(typeDefinition.body() instanceof TupleBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not tuple-shaped: " + typeDefinition.body());
            }
            String displayName = EntryDisplayName.of(name, typeDefinition);
            DataClass dataClass;
            try {
                dataClass = descriptorFor(name, typeDefinition, context);
            } catch (MissingBindingException missing) {
                return unbound(name, displayName, body, missing, resolver, context.locationOf(name, typeDefinition),
                        AnnotationTypes.of(context));
            }
            if (!(dataClass instanceof DataClassTuple descriptor)) {
                throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                        + ", which isn't tuple-shaped -- can't bind '" + name + "' as one");
            }
            List<String> mismatches = disagreements(body, descriptor);
            if (!mismatches.isEmpty()) {
                throw new BindMismatchException("'" + displayName + "' and " + descriptor.typeClass().getName()
                        + " do not agree: " + String.join("; ", mismatches));
            }
            return new TupleBindReader(name, displayName, body, descriptor, resolver,
                    context.locationOf(name, typeDefinition),
                    AnnotationTypes.of(context));
        }

        private DataClass descriptorFor(String name, TypeDefinition definition, ValueReaderContext vctx) {
            DataBindException first = null;
            StringBuilder tried = new StringBuilder();
            for (String candidate : vctx.bindingNamesFor(name, definition)) {
                try {
                    return context.getDescriptor(candidate);
                } catch (DataBindException e) {
                    first = first == null ? e : first;
                    tried.append(tried.isEmpty() ? "" : "' or '").append(candidate);
                }
            }
            // A misconfiguration, not a gap -- see RecordBindReader.Factory.descriptorFor.
            throw new MissingBindingException("no bound Java class for '" + tried + "': nothing in this "
                    + "bind context resolves that schema type name. Map it (ProcessorConfig.bindings) or give "
                    + "the context a DataNameBinder that can find it -- "
                    + (first == null ? "" : first.getMessage()), first);
        }
    }
}
