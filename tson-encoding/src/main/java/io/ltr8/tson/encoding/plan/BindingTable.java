package io.ltr8.tson.encoding.plan;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.encoding.TsonTypeBinding;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The answers a binding holds, loaded once: each entry the schema reaches that the binder binds, with its class
 * described, and for each it does not, why. A query never consults the context, so a table is immutable once
 * loaded and may be queried from any thread.
 *
 * <p><b>Two loads, one table.</b> A strict load ({@link #strict}) is a deployment's: a class the binder names and the
 * context cannot describe fails it, since every read of that entry would. A checkpoint's ({@link #checkpoint}) is a
 * resolution's: its namespace is still being built and the bind context is the consumer's, so a chain reaching no
 * entry is passed over and a class that cannot be described is held as absent, the read reaching it failing --
 * a consumer's class must not fail resolving a schema, nor a tree read of it.
 */
public final class BindingTable implements EntryBindings {

    private final TsonLinkedSchema schema;
    private final DataBindContext context;
    private final Map<EntryKey, TsonTypeBinding> bound;
    private final Map<String, EntryKey> keys;
    private final Map<EntryKey, String> absent;
    private final Map<Class<?>, List<TsonTypeBinding>> byClass;

    private BindingTable(TsonLinkedSchema schema, DataBindContext context, Map<EntryKey, TsonTypeBinding> bound,
                         Map<String, EntryKey> keys, Map<EntryKey, String> absent,
                         Map<Class<?>, List<TsonTypeBinding>> byClass) {
        this.schema = schema;
        this.context = context;
        this.bound = bound;
        this.keys = keys;
        this.absent = absent;
        this.byClass = byClass;
    }

    /**
     * {@code schema}'s bindings under {@code context}, for a compile of it that reads data.
     *
     * @throws BindMismatchException where the binder names a class the context cannot describe, or fails
     * @throws IllegalStateException where an alias chain of {@code schema} reaches no entry
     */
    public static BindingTable strict(TsonLinkedSchema schema, DataBindContext context) {
        return load(schema, context, true);
    }

    /** {@code schema}'s bindings under {@code context}, for a compile of a resolution in progress. */
    public static BindingTable checkpoint(TsonLinkedSchema schema, DataBindContext context) {
        return load(schema, context, false);
    }

    public TsonLinkedSchema schema() {
        return schema;
    }

    public DataBindContext context() {
        return context;
    }

    @Override
    public Optional<TsonTypeBinding> get(EntryKey key) {
        return Optional.ofNullable(bound.get(key));
    }

    @Override
    public Optional<TsonTypeBinding> get(String writtenName) {
        EntryKey key = keys.get(writtenName);
        return key == null ? Optional.empty() : get(key);
    }

    @Override
    public TsonTypeBinding require(EntryKey key) {
        TsonTypeBinding binding = bound.get(key);
        if (binding != null) {
            return binding;
        }
        String reason = absent.get(key);
        if (reason == null) {
            throw new IllegalArgumentException("'" + key.name() + "' of " + key.declaringSchema()
                    + " is no entry " + schema.identity() + " reaches");
        }
        throw new MissingBindingException(reason);
    }

    /**
     * The binding of the one entry {@code type} is bound as.
     *
     * @throws BindMismatchException where several binding candidates bind {@code type}
     */
    public Optional<TsonTypeBinding> get(Class<?> type) {
        List<TsonTypeBinding> bindings = byClass.getOrDefault(type, List.of());
        if (bindings.isEmpty()) {
            return Optional.empty();
        }
        List<EntryKey> boundAs = bindings.stream().map(TsonTypeBinding::boundAs).distinct().toList();
        if (boundAs.size() > 1) {
            throw new BindMismatchException(type.getName() + " is bound as " + boundAs.size() + " entries ("
                    + String.join(", ", boundAs.stream().map(BindingTable::named).toList()) + "), so which of them a "
                    + "value of it is cannot be told from its class: bind each to a class of its own, or ask "
                    + "bindingsOf(" + type.getSimpleName() + ") and choose among them");
        }
        return Optional.of(bindings.stream().filter(binding -> binding.key().equals(binding.boundAs())).findFirst()
                .orElse(bindings.getFirst()));
    }

    /** Every entry bound to {@code type}, in the schema's declaration order. */
    public List<TsonTypeBinding> bindingsOf(Class<?> type) {
        return byClass.getOrDefault(type, List.of());
    }

    private static BindingTable load(TsonLinkedSchema schema, DataBindContext context, boolean strict) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(context, "context");
        Map<EntryKey, Answer> asked = new HashMap<>();
        Map<EntryKey, TsonTypeBinding> bound = new HashMap<>();
        Map<String, EntryKey> keys = new HashMap<>();
        Map<EntryKey, String> absent = new HashMap<>();
        Map<Class<?>, List<TsonTypeBinding>> byClass = new LinkedHashMap<>();
        for (String name : schema.schema().entries().keySet()) {
            Optional<EntryKey> reached = schema.entryKey(name);
            if (reached.isEmpty()) {
                if (strict) {
                    throw new IllegalStateException("'" + name + "' of " + schema.identity() + " reaches no entry, "
                            + "which linking refuses");
                }
                continue;
            }
            EntryKey key = reached.get();
            keys.put(name, key);
            if (bound.containsKey(key) || absent.containsKey(key)) {
                continue;
            }
            Top definition = schema.landing(name).map(ExternTarget.Landing::in).orElse(schema).schema().entries()
                    .get(key.name());
            if (definition instanceof RecordBody record && record.isParametric()) {
                absent.put(key, "'" + key.name() + "' is a parametric record, which nothing reads bare "
                        + "([TSON-SCHEMA] §5.10): a value is read through an application of it, bound under the name "
                        + "the application is declared by");
                continue;
            }
            List<EntryKey> candidates = schema.bindingCandidates(name);
            Answer answer = null;
            for (EntryKey candidate : candidates) {
                answer = asked.computeIfAbsent(candidate, at -> ask(at, context));
                if (!(answer instanceof Answer.Unbound)) {
                    break;
                }
            }
            switch (answer) {
                case Answer.Bound found -> {
                    TsonTypeBinding binding = new TsonTypeBinding(key, definition, found.dataClass(), found.as());
                    bound.put(key, binding);
                    byClass.computeIfAbsent(found.dataClass().typeClass(), type -> new ArrayList<>()).add(binding);
                }
                case Answer.Refused refused -> {
                    String reason = named(key, refused.as()) + " " + refused.reason();
                    if (strict) {
                        throw new BindMismatchException(reason, refused.cause());
                    }
                    absent.put(key, reason);
                }
                case Answer.Unbound ignored -> absent.put(key, "no bound Java class for '" + String.join("' or '",
                        candidates.stream().map(EntryKey::name).toList()) + "': nothing in this bind context resolves "
                        + "that schema type name. Map it (ProcessorConfig.withDataBindContext) or give the context a "
                        + "DataNameBinder that can find it -- " + ((Answer.Unbound) asked.get(candidates.getFirst()))
                        .reason());
                case null -> throw new IllegalStateException("'" + name + "' has no binding candidate");
            }
        }
        Map<Class<?>, List<TsonTypeBinding>> classes = new HashMap<>();
        byClass.forEach((type, bindings) -> classes.put(type, List.copyOf(bindings)));
        return new BindingTable(schema, context, Map.copyOf(bound), Map.copyOf(keys), Map.copyOf(absent),
                Map.copyOf(classes));
    }

    /** What the binder answers for one candidate, held for the length of a load. */
    private sealed interface Answer {

        record Bound(DataClass dataClass, EntryKey as) implements Answer {
        }

        /** The binder binds no class; {@code reason} is its own sentence, saying what it does bind. */
        record Unbound(String reason) implements Answer {
        }

        /** The binder names a class the context cannot describe, or fails asked. */
        record Refused(EntryKey as, String reason, Throwable cause) implements Answer {
        }
    }

    private static Answer ask(EntryKey candidate, DataBindContext context) {
        Class<?> target;
        try {
            target = context.nameBinder().resolve(candidate.declaringSchema(), candidate.name());
        } catch (DataBindException unbound) {
            return new Answer.Unbound(unbound.getMessage());
        } catch (RuntimeException broken) {
            return new Answer.Refused(candidate, "could not be asked of the binder, which failed: " + broken
                    + ". A DataNameBinder answers a name it binds no class for by throwing DataBindException", broken);
        }
        try {
            return new Answer.Bound(context.getDescriptor(target), candidate);
        } catch (DataBindException | RuntimeException unanalysable) {
            return new Answer.Refused(candidate, "binds " + target.getName() + ", which cannot be analysed: "
                    + reasons(unanalysable) + ". Bind '" + candidate.name() + "' to a class this bind context can "
                    + "describe, or leave it out of the binding map", unanalysable);
        }
    }

    /** {@code key}, and where the binder bound it under another name, that name. */
    private static String named(EntryKey key, EntryKey as) {
        return named(key) + (as.equals(key) ? "" : ", bound as '" + as.name() + "',");
    }

    private static String named(EntryKey key) {
        return "'" + key.name() + "' of " + key.declaringSchema();
    }

    /**
     * Each distinct message along {@code e}'s cause chain, outermost first: a wrapper saying only that something
     * failed leaves what failed to its cause.
     */
    private static String reasons(Throwable e) {
        StringBuilder reasons = new StringBuilder();
        for (Throwable at = e; at != null; at = at.getCause() == at ? null : at.getCause()) {
            String message = at.getMessage() != null ? at.getMessage() : at.toString();
            if (reasons.indexOf(message) < 0) {
                reasons.append(reasons.isEmpty() ? "" : ": ").append(message);
            }
        }
        return reasons.toString();
    }
}
