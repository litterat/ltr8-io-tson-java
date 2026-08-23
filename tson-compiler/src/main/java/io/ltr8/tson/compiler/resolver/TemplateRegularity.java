package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §5.10's regularity boundary, checked where a template is <em>declared</em>: within a template body, a
 * recursive application -- direct or mutual -- must pass each parameter through unchanged.
 *
 * <p><b>Why a static rule rather than a runtime limit.</b> Non-regular recursion grows its argument every
 * level ({@code weird => <T> { next: weird<box<T>>? } } reaches {@code weird<box<text>>}, then
 * {@code weird<box<box<text>>>}, …), so every instantiation is distinct, dedup-by-identity never fires, and
 * there is no finite set of types to build. Caught only while materialising, it costs a depth counter --
 * a non-portable limit, and the same retrofit C++ reached for after shipping templates without a regularity
 * restriction. Caught here it is an ordinary schema error at the line that wrote it, and a template nobody
 * ever applies is still rejected rather than shipping broken.
 *
 * <p><b>Mutual recursion needs reachability, not just a self-edge.</b> {@code a => <T> { b: b_t<box<T>> } }
 * with {@code b_t => <U> { a: a_t<U> } } grows the argument across the cycle, so an application is checked
 * whenever its head can reach the declaration it sits in.
 *
 * <p><b>Deliberately stricter than termination requires.</b> The condition that actually bounds the work is
 * weaker -- every argument a bare parameter reference, at any position -- because arguments are only ever
 * copied, never constructed, so permuting ({@code swap => <A, B> { x: swap<B, A> } }) or duplicating
 * ({@code dup => <A, B> { x: dup<A, A> } }) still reaches finitely many instantiations. Positional identity
 * is what §5.10's cited precedent uses (ML restricts polymorphic recursion the same way) and what the
 * change report proposes, and an over-restriction that is simple to state can be loosened later; the
 * reverse cannot. Recorded rather than silently narrowed -- {@code BACKLOG.md} carries the finding.
 *
 * <p>Arity is <em>not</em> checked here. A never-applied template's arity is still unverified, which is its
 * own gap; this compares positionally only where the arity already matches.
 */
final class TemplateRegularity {

    private TemplateRegularity() {
    }

    /**
     * Checks every template in {@code entries}, reporting each irregular application against the declaration
     * that wrote it. {@code reporter} may be {@code null}, in which case the first failure throws.
     *
     * @return the templates found irregular, for the caller to replace before anything closes an application
     *         of one. A condemned template left in place materialises exactly as far as the depth backstop
     *         allows and reports again from there -- one defect, two diagnostics, the second of them 64
     *         instantiations long. Always empty when {@code reporter} is {@code null}, that mode having
     *         thrown at the first failure.
     */
    static Set<String> check(Map<String, TypeDefinition> entries, Reporter reporter) {
        Set<String> irregular = new LinkedHashSet<>();
        Map<String, List<Application>> applications = new LinkedHashMap<>();
        for (Map.Entry<String, TypeDefinition> entry : entries.entrySet()) {
            if (!entry.getValue().parameters().isEmpty()) {
                applications.put(entry.getKey(), applicationsIn(entry.getValue()));
            }
        }
        for (Map.Entry<String, List<Application>> template : applications.entrySet()) {
            String name = template.getKey();
            List<String> parameters = entries.get(name).parameters();
            for (Application application : template.getValue()) {
                if (!reaches(application.head(), name, applications)) {
                    continue; // not on a cycle back to this declaration -- nothing to keep regular
                }
                String problem = irregularity(name, parameters, application);
                if (problem == null) {
                    continue;
                }
                TsonSchemaValidationException error = new TsonSchemaValidationException(problem);
                if (reporter == null) {
                    throw error;
                }
                reporter.reportIrregularRecursion(name, error);
                irregular.add(name);
            }
        }
        return irregular;
    }

    /** Where an irregular recursive application is reported, per declaration. */
    @FunctionalInterface
    interface Reporter {
        void reportIrregularRecursion(String declaration, TsonSchemaValidationException error);
    }

    /** One application written inside a template body: the head it applies and the arguments it applies to. */
    private record Application(String head, List<TypeArgument> arguments) {
    }

    /**
     * The reason this recursive application is irregular, or {@code null} when it passes every parameter
     * through unchanged. Arity mismatch yields {@code null}: comparing positionally needs the positions to
     * line up, and an arity error is a different verdict reported elsewhere.
     */
    private static String irregularity(String name, List<String> parameters, Application application) {
        if (application.arguments().size() != parameters.size()) {
            return null;
        }
        for (int i = 0; i < parameters.size(); i++) {
            TypeArgument argument = application.arguments().get(i);
            if (argument instanceof TypeArgument.Ref ref && ref.ref().arguments().isEmpty()
                    && ref.ref().name().equals(parameters.get(i))) {
                continue;
            }
            return "'" + name + "' applies '" + application.head() + "' recursively but does not pass '"
                    + parameters.get(i) + "' through unchanged: argument " + (i + 1) + " is " + shown(argument)
                    + ". A recursive application must be applied to the enclosing declaration's own "
                    + "parameters, in order (§5.10) -- otherwise the argument grows at every level and there "
                    + "is no finite set of types to build";
        }
        return null;
    }

    private static String shown(TypeArgument argument) {
        return switch (argument) {
            case TypeArgument.Ref ref -> ref.ref().arguments().isEmpty()
                    ? "'" + ref.ref().name() + "'"
                    : "the application '" + ref.ref().name() + "<...>'";
            case TypeArgument.Value value -> "the literal '" + value.value().text() + "'";
        };
    }

    /** Whether {@code from} reaches {@code target} by applying templates, {@code from == target} included. */
    private static boolean reaches(String from, String target, Map<String, List<Application>> applications) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(from);
        while (!pending.isEmpty()) {
            String head = pending.pop();
            if (head.equals(target)) {
                return true;
            }
            if (!seen.add(head)) {
                continue;
            }
            for (Application application : applications.getOrDefault(head, List.of())) {
                pending.push(application.head());
            }
        }
        return false;
    }

    /**
     * Every application a template's body writes, including ones nested inside another application's
     * arguments ({@code box<chain<T>>} at a field position applies both).
     *
     * <p>The body walk is {@code TemplateMaterialiser.mapBodyRefs}, used as a visitor by returning each ref
     * unchanged -- one place knows the shape of a body, so a new body type cannot be handled here and
     * forgotten there.
     */
    private static List<Application> applicationsIn(TypeDefinition template) {
        List<Application> found = new ArrayList<>();
        TemplateMaterialiser.mapBodyRefs(template.body(), ref -> {
            collect(ref, found);
            return ref;
        });
        return found;
    }

    private static void collect(TypeRef ref, List<Application> found) {
        if (!ref.arguments().isEmpty()) {
            found.add(new Application(ref.name(), ref.arguments()));
        }
        for (TypeArgument argument : ref.arguments()) {
            if (argument instanceof TypeArgument.Ref nested) {
                collect(nested.ref(), found);
            }
        }
    }
}
