package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * [TSON-SCHEMA] §5.10's <b>two parameter kinds, inferred by use</b>: a parameter standing at a type-reference
 * position is a {@code TYPE} parameter, one standing in a scalar slot of a held constructor body is a
 * {@code VALUE} parameter, and one standing anywhere else is a resolver error at the declaration.
 *
 * <p><b>The slot's declared type is what says which.</b> A held body is the constructor application as
 * written, so the body alone cannot tell a parameter naming a type from one standing for an enum member --
 * both are bare tokens. What separates them is the constructor's own vocabulary: {@code array.element_type}
 * is typed {@code type_ref}, {@code enum.members} is a set of {@code identifier}, {@code record_field.value}
 * is typed {@code value}. §9 makes that reading general rather than a table of kernel names -- a slot holding
 * a type reference MUST be typed {@code type_ref} -- so an extension meta-schema's own constructors classify
 * by the same walk with nothing added here.
 *
 * <p><b>Three outcomes per occurrence</b>, and the third is why this is not one pass over one declaration:
 *
 * <ul>
 *   <li>the slot is typed {@code type_ref} -- a {@code TYPE} parameter;</li>
 *   <li>the slot's type resolves to an {@link Atom} (which covers {@code identifier}, {@code value} and every
 *   enum) -- a {@code VALUE} parameter;</li>
 *   <li>the parameter rides another template's argument list, where meta-kernel's own {@code type_argument}
 *   doc says a parameter of <em>either</em> kind travels on the reference channel. That occurrence says
 *   nothing on its own: the kind comes from the callee's parameter at that position, so it is recorded as a
 *   dependency and settled by a fixed point over the whole open-entry set.</li>
 * </ul>
 *
 * <p>Anything else -- a parameter standing where a record, a collection or a choice is declared, as in
 * {@code <T> !enum { members: T }} -- is refused here. §5.10 confines value parameters to scalars and type
 * parameters to references, so a parameter standing for a whole {@code enum_set} is neither, and refusing it
 * at the declaration is what turns "every application of this fails" into "this template is wrong".
 *
 * <p><b>A parameter the fixed point leaves undetermined is a type parameter, and that is forced</b> -- see
 * {@code groundRemainingAsType}. §5.10 makes such a parameter a resolver error ("grounded only in mutual
 * recursion between templates, with no concrete kind-determining use"); this implementation reads it as
 * having one consistent assignment instead, because being a value parameter <em>means</em> standing in a
 * scalar slot and a slot is what grounds a parameter. {@code SPEC-FEEDBACK.md} #20 carries the divergence.
 */
final class ParameterKinds {

    /** The kernel entry every type-reference slot is typed by ([TSON-SCHEMA] §9). */
    private static final String TYPE_REF = "type_ref";

    /** What a parameter's occurrences make it (§5.10). */
    enum Kind { TYPE, VALUE }

    private ParameterKinds() {
    }

    /**
     * Every open entry's parameter kinds, by entry name then parameter name.
     *
     * <p>{@code entries} is the <b>whole namespace</b>, imports included, because a local template may route
     * a parameter into an imported one and take its kind from there. {@code declared} is the subset this
     * schema wrote, and the only names a failure is reported against: an imported entry resolved in its own
     * schema, and reporting it here would put one document's verdict on another's declaration.
     */
    static Map<String, Map<String, Kind>> inferAll(Map<String, TypeDefinition> entries, Set<String> declared,
                                                    Function<String, TypeDefinition> meta,
                                                    FailureReporter reporter) {
        Map<String, Occurrences> observed = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (definition.parameters().isEmpty() || !(definition.body() instanceof HeldBody held)) {
                return;
            }
            Occurrences occurrences = new Occurrences(definition.parameters());
            try {
                new Walk(occurrences, meta).body(held);
            } catch (TsonSchemaValidationException e) {
                if (declared.contains(name)) {
                    reporter.report(name, e);
                }
                return;
            }
            observed.put(name, occurrences);
        });
        return settle(observed, entries, declared, reporter);
    }

    /**
     * One template's kinds from its own body alone, for a caller with no batch pass behind it.
     *
     * <p>{@link #inferAll} runs once every declaration has resolved, which is after resolution has already
     * closed some applications on demand -- a composition supertype and a refinement source have to absorb
     * the closed entry's fields and cannot wait for the batch. Those closings ask for this instead: the
     * template in hand has resolved, so its own occurrences classify, and only a parameter needing the
     * cross-template fixed point is left undetermined. A body that will not classify yields nothing here and
     * is reported by the batch pass, which is the one that knows which declarations this schema wrote.
     */
    static Map<String, Kind> inferOne(TypeDefinition template, Function<String, TypeDefinition> meta) {
        if (template.parameters().isEmpty() || !(template.body() instanceof HeldBody held)) {
            return Map.of();
        }
        Occurrences occurrences = new Occurrences(template.parameters());
        try {
            new Walk(occurrences, meta).body(held);
        } catch (TsonSchemaValidationException e) {
            return Map.of();
        }
        return occurrences.conflict == null ? occurrences.kinds : Map.of();
    }

    /** Where a declaration whose parameters will not classify is reported, entry by entry. */
    @FunctionalInterface
    interface FailureReporter {
        void report(String entryName, TsonSchemaValidationException error);
    }

    // ── The fixed point ──────────────────────────────────────────────────────────────────────────

    /**
     * Deferred occurrences resolved against the kinds already known, until nothing moves. A parameter riding
     * {@code box<T>}'s argument list takes {@code box}'s own parameter kind at that position, and {@code box}
     * may itself be waiting on this one -- §5.10 anticipates the cycle and calls a parameter grounded only by
     * it an error; this pass leaves it undetermined instead, which is the conservative half of that rule.
     */
    private static Map<String, Map<String, Kind>> settle(Map<String, Occurrences> observed,
                                                          Map<String, TypeDefinition> entries,
                                                          Set<String> declared, FailureReporter reporter) {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Occurrences occurrences : observed.values()) {
                for (Deferred deferred : occurrences.deferred) {
                    Occurrences callee = observed.get(deferred.head);
                    if (callee == null) {
                        continue;
                    }
                    List<String> calleeParameters = entries.get(deferred.head).parameters();
                    if (deferred.index >= calleeParameters.size()) {
                        continue; // an arity error, which the materialiser reports where it is applied
                    }
                    Kind kind = callee.kinds.get(calleeParameters.get(deferred.index));
                    if (kind != null && occurrences.observe(deferred.parameter, kind)) {
                        moved = true;
                    }
                }
            }
        }
        observed.values().forEach(Occurrences::groundRemainingAsType);
        Map<String, Map<String, Kind>> result = new LinkedHashMap<>();
        observed.forEach((name, occurrences) -> {
            if (occurrences.conflict != null) {
                if (declared.contains(name)) {
                    reporter.report(name, occurrences.conflict);
                }
                return;
            }
            result.put(name, occurrences.kinds);
        });
        return result;
    }

    /** One parameter riding another template's argument list, whose kind that template's parameter fixes. */
    private record Deferred(String parameter, String head, int index) {
    }

    /** What one declaration's occurrences have made of its parameters so far. */
    private static final class Occurrences {

        private final List<String> parameters;
        private final Map<String, Kind> kinds = new LinkedHashMap<>();
        private final List<Deferred> deferred = new ArrayList<>();
        private TsonSchemaValidationException conflict;

        Occurrences(List<String> parameters) {
            this.parameters = parameters;
        }

        boolean declares(String name) {
            return parameters.contains(name);
        }

        /**
         * A parameter the fixed point left undetermined is a <b>type</b> parameter, and that is forced rather
         * than chosen. Being a value parameter means standing in a scalar slot of some held body -- that is
         * §5.10's definition of one -- and a slot is exactly what grounds a parameter. So a parameter with no
         * concrete use anywhere in its cycle cannot be a value parameter, and TYPE is the only assignment
         * consistent with every occurrence.
         *
         * <p>{@code loop => <T> loop<T>} is the case that cannot be written any other way: a reference
         * template's body <em>is</em> the application, so there is no second slot to put a concrete use in,
         * and T is passed only to the parameter it is. §5.10 makes such a parameter a resolver error; this
         * implementation reads the assignment as forced instead and leaves the declaration to be judged on
         * what is actually wrong with it -- for that one, that it applies itself forever.
         */
        void groundRemainingAsType() {
            for (String parameter : parameters) {
                if (!deferred.isEmpty() && !kinds.containsKey(parameter)) {
                    kinds.putIfAbsent(parameter, Kind.TYPE);
                }
            }
        }


        /** Records one occurrence's verdict, returning whether it added anything. */
        boolean observe(String parameter, Kind kind) {
            Kind previous = kinds.putIfAbsent(parameter, kind);
            if (previous == null) {
                return true;
            }
            if (previous != kind && conflict == null) {
                conflict = new TsonSchemaValidationException("parameter '" + parameter + "' stands in both a "
                        + "type position and a value position, so no argument can satisfy both -- §5.10 gives "
                        + "a parameter one kind, inferred from where it is used");
            }
            return false;
        }
    }

    // ── The walk ─────────────────────────────────────────────────────────────────────────────────

    /** One held body walked against the vocabulary of the constructor it applies. */
    private record Walk(Occurrences occurrences, Function<String, TypeDefinition> meta) {

        void body(HeldBody held) {
            String head = held.application().typeRef().orElse(null);
            if (head == null || !(held.application().coreValue() instanceof RecordValue wire)) {
                return; // not a constructor application -- nothing here classifies
            }
            if (resolve(head) instanceof RecordBody vocabulary) {
                record(wire, vocabulary);
            }
        }

        /** Each written slot walked against the field the constructor declares for it. */
        private void record(RecordValue wire, RecordBody vocabulary) {
            for (RecordValue.Field written : wire.fields()) {
                vocabulary.fields().stream().filter(f -> f.name().equals(written.name())).findFirst()
                        .ifPresent(field -> value(written.value().value().coreValue(), field.type()));
            }
        }

        /** One written value against the type its slot declares. */
        private void value(CoreValue written, TypeRef declared) {
            String slot = declared.name();
            Top type = resolve(slot);
            switch (written) {
                case TokenValue token when occurrences.declares(token.text()) -> token(token.text(), slot, type);
                case ArrayValue array -> elements(array, type);
                case RecordValue record when TYPE_REF.equals(slot) -> application(record);
                case RecordValue record when type instanceof RecordBody nested -> record(record, nested);
                default -> {
                }
            }
        }

        /** A parameter standing at a slot: the slot's declared type is the whole of the verdict. */
        private void token(String parameter, String slot, Top type) {
            if (TYPE_REF.equals(slot)) {
                occurrences.observe(parameter, Kind.TYPE);
            } else if (type instanceof Atom) {
                occurrences.observe(parameter, Kind.VALUE);
            } else {
                throw new TsonSchemaValidationException("parameter '" + parameter + "' stands where '" + slot
                        + "' is declared, which is neither a type reference nor a scalar -- §5.10 binds a "
                        + "value parameter to scalars only and a type parameter to references, so nothing "
                        + "could be applied here");
            }
        }

        private void elements(ArrayValue array, Top type) {
            TypeRef element = switch (type) {
                case ArrayBody arrayBody -> arrayBody.elementType();
                default -> null;
            };
            if (element == null && !(type instanceof TupleBody)) {
                return; // not a collection slot -- a shape error the constructor's own reader reports
            }
            for (int i = 0; i < array.elements().size(); i++) {
                TypeRef declared = element != null ? element
                        : ((TupleBody) type).elements().get(Math.min(i, ((TupleBody) type).elements().size() - 1))
                                .elementType();
                value(array.elements().get(i).value().coreValue(), declared);
            }
        }

        /**
         * A slot typed {@code type_ref} holding an application rather than a bare name. The head is a type
         * name; each argument that names a parameter of this declaration is <b>deferred</b>, since
         * meta-kernel's {@code type_argument} puts a parameter of either kind on the reference channel.
         *
         * <p>The members are read through {@link WireForm}, not by matching {@code "name"} and
         * {@code "arguments"} here: what an application looks like on the wire is one class's answer, and a
         * walk that re-derives it is a second opinion waiting to disagree.
         */
        private void application(RecordValue application) {
            String head = WireForm.field(application, WireForm.NAME)
                    .filter(TokenValue.class::isInstance).map(v -> ((TokenValue) v).text()).orElse(null);
            Optional<CoreValue> arguments = WireForm.field(application, WireForm.ARGUMENTS);
            if (head == null || arguments.isEmpty() || !(arguments.get() instanceof ArrayValue list)) {
                return;
            }
            for (int i = 0; i < list.elements().size(); i++) {
                argument(list.elements().get(i).value().coreValue(), head, i);
            }
        }

        /** One {@code type_argument}: a bare parameter name under {@code name} defers, everything else does not. */
        private void argument(CoreValue argument, String head, int index) {
            if (!(argument instanceof RecordValue record)) {
                return;
            }
            for (RecordValue.Field member : record.fields()) {
                CoreValue value = member.value().value().coreValue();
                if (member.name().equals(WireForm.VALUE)) {
                    continue; // a literal argument says nothing about this declaration's parameters
                }
                if (value instanceof TokenValue token && occurrences.declares(token.text())) {
                    occurrences.deferred.add(new Deferred(token.text(), head, index));
                } else if (value instanceof RecordValue nested) {
                    application(nested); // a nested application: `box<inner<T>>`
                }
            }
        }

        /** A name's resolved body, reference chains followed -- the slot type a written value faces. */
        private Top resolve(String name) {
            TypeDefinition definition = meta.apply(name);
            for (int hops = 0; definition != null && definition.body() instanceof Reference reference
                    && hops < 32; hops++) {
                definition = meta.apply(reference.target().name());
            }
            return definition == null ? null : definition.body();
        }
    }
}
