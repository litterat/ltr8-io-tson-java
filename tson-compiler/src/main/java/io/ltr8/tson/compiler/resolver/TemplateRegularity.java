package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Top;
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
 * <p><b>Growth is unwritable before this runs.</b> An argument is a name or a parameter (§5.3), so {@code weird => <T>
 * { next?: weird<box<T>> } } is refused where its field resolves, and every application a closing reaches draws its
 * arguments from the schema's finitely many names: each recursion repeats an application, which ties to the
 * declaration closing it or is refused where none does ({@code TemplateMaterialiser}). What this rule still refuses
 * is a recursive application that permutes the parameters ({@code swap => <A, B> { x?: swap<B, A> } }), duplicates
 * one ({@code dup => <A, B> { x?: dup<A, A> } }) or passes a name for one ({@code c => <T> { next?: c<text> } }), at
 * the declaration, applied or not: with it a cycle of heads is a cycle of applications, which is what lets {@link
 * HeldRecursion} judge at the declaration from the heads alone. It is §5.10.1's positional identity, the restriction
 * ML makes of polymorphic recursion. {@code TemplateRegularityTest.permutingParametersIsRejectedThoughItWouldTerminate}
 * pins it.
 *
 * <p><b>Mutual recursion needs reachability, not just a self-edge.</b> Neither template in an {@code a → b → a}
 * cycle applies itself, so an application is checked whenever its head can reach the declaration it sits in.
 *
 * <p>Arity is <em>not</em> checked here: {@code TsonSchemaLinker} checks it over every application an open record
 * writes, whether or not the template is ever applied. This compares positionally only where the arity already
 * matches.
 */
final class TemplateRegularity {

    private TemplateRegularity() {
    }

    /**
     * Checks every template in {@code entries}, reporting each irregular application against the declaration
     * that wrote it. {@code reporter} may be {@code null}, in which case the first failure throws.
     *
     * @return the templates found irregular, for the caller to replace before anything closes an application
     *         of one, so an application of one reports nothing further. Always empty when {@code reporter} is
     *         {@code null}, that mode having thrown at the first failure.
     */
    static Set<String> check(Map<String, Top> entries, Reporter reporter) {
        Set<String> irregular = new LinkedHashSet<>();
        Map<String, List<Application>> applications = new LinkedHashMap<>();
        for (Map.Entry<String, Top> entry : entries.entrySet()) {
            if (!OpenEntry.parameterNames(entry.getValue()).isEmpty()) {
                applications.put(entry.getKey(), applicationsIn(entry.getValue()));
            }
        }
        for (Map.Entry<String, List<Application>> template : applications.entrySet()) {
            String name = template.getKey();
            List<String> parameters = OpenEntry.parameterNames(entries.get(name));
            for (Application application : template.getValue()) {
                if (!reaches(application.head(), name, applications)) {
                    continue; // not on a cycle back to this declaration -- nothing to keep regular
                }
                String problem = irregularity(name, parameters, application);
                if (problem == null) {
                    continue;
                }
                SchemaValidationException error = new SchemaValidationException(problem);
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
        void reportIrregularRecursion(String declaration, SchemaValidationException error);
    }

    /** One application written inside a template body: the head it applies and the arguments it applies to. */
    private record Application(String head, List<TypeRef> arguments) {
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
            TypeRef argument = application.arguments().get(i);
            if (argument.name().equals(parameters.get(i))) {
                continue;
            }
            return "'" + name + "' applies '" + application.head() + "' recursively but does not pass '"
                    + parameters.get(i) + "' through unchanged: argument " + (i + 1) + " is '" + argument.text() + "'"
                    + ". A recursive application must be applied to the enclosing declaration's own "
                    + "parameters, in order (§5.10) -- otherwise the argument grows at every level and there "
                    + "is no finite set of types to build";
        }
        return null;
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
     * Every application a template's body writes, at any depth of types written in place.
     *
     * <p>Every entry with parameters is an open record, which answers which applications it writes ({@link
     * OpenEntry#applications}), the same kind of declared question as its names, which is what keeps this rule at the
     * declaration, before anything is resolved against it.
     */
    private static List<Application> applicationsIn(Top template) {
        return OpenEntry.applications(template).stream()
                .map(application -> new Application(application.head().name(), application.arguments())).toList();
    }
}
