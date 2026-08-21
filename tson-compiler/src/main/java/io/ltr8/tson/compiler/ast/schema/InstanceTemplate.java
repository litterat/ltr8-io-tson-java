package io.ltr8.tson.compiler.ast.schema;

import java.util.List;

/**
 * {@code instance-template = type-params ws "!" type-name ws template-def} (Part 2 §12.1) -- a template
 * whose body is a constructor application, so {@code vector => <T, N> !array { element_type: T  min_items:
 * N  max_items: N }} is a declaration.
 *
 * <p><b>A production of its own, not {@code [type-params] instance}.</b> The surface syntax is the same --
 * a {@code !} head over a braced payload -- but the two resolve against different vocabulary: an
 * {@link Instance} binds its payload through the <em>constructor's</em> own reader and yields that
 * constructor's body, while this yields an {@code instance_template}. Same brace, different destination.
 * {@link Instance} therefore stays unparameterised, which also means nothing gains an optional parameter
 * list that could be silently dropped.
 *
 * <p><b>Why it exists at all.</b> Every other {@code type-def} alternative can be templated; a constructor
 * application could not, which left a template that builds an {@code !array} with no source spelling. The
 * only grammatical route to one was refinement over a constructor ({@code array ^ { min_items: = S }}), the
 * size-template shape this revision deletes.
 *
 * <p><b>It is the fallback spelling.</b> For the four sugared constructors the compact form already exists
 * -- {@code vector => <T, N> [T; N]} -- and this is the route to a constructor with no sugar ({@code set},
 * and whatever a meta layer adds), as well as the target those sugar forms desugar into.
 */
public record InstanceTemplate(List<String> typeParams, String target, List<TemplateBinding> bindings)
        implements TypeDef {

    public InstanceTemplate {
        typeParams = List.copyOf(typeParams);
        bindings = List.copyOf(bindings);
        if (typeParams.isEmpty()) {
            throw new IllegalArgumentException("an instance template carries at least one type parameter; "
                    + "without one it is an ordinary Instance");
        }
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("an instance template binds at least one field");
        }
    }
}
