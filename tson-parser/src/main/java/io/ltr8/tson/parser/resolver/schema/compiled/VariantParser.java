package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Set;

/**
 * Wraps a declaration's own (already-compiled) body parser with dispatch-by-{@code !typeName} over
 * its known subtypes ({@link TypeDefinition#subtypes}, populated by {@code
 * SchemaValidator.computeSubtypes} from the reverse composition index -- already the full
 * transitive closure, so a flat name lookup here covers the whole subtype hierarchy, not just
 * direct children).
 *
 * <p><b>The declaration's own body is always a valid reading, not just a fallback bolted on.</b> A
 * value with no type-ref, or one naming the declaration itself, reads directly against {@code
 * ownParser} -- the same parser it would have gotten if it had no subtypes at all. This matters for
 * a genuinely common shape: {@code top => top & {}} has both an empty body of its own *and* a huge
 * subtype list (everything else in meta-kernel composes with it, directly or transitively) --
 * {@code body: {}} at a bare {@code top}-typed position is a real, meaningful value (§4.1's own
 * base case), not an error demanding a type annotation, and reading it that way falls out for free
 * here rather than needing {@code top} special-cased anywhere. The same reasoning applies to any
 * other type with a genuinely readable own shape and known subtypes (a fresh composition like
 * {@code atom => top & {}}, or {@code array}'s own vocabulary, which {@code set}/{@code array_min}/
 * etc. all refine) -- there's nothing {@code top}-specific in this class at all.
 *
 * <p>An earlier version of this class instead required an explicit type-ref unconditionally, on
 * the theory that a type with subtypes (originally triggered by *open type parameters*, not
 * subtypes directly) had "no concrete shape of its own to read" -- true for a genuinely open
 * template with no useful body, but not a safe general assumption: {@code top}/{@code atom}/{@code
 * array} all have subtypes *and* a perfectly good body of their own. Triggering on non-empty
 * {@code subtypes} instead of non-empty {@code parameters} (see {@link TsonSchemaParser}'s own
 * Javadoc) is what makes the "always compile the own body too" version of this class correct: the
 * signal for "this position might need dispatch" and the signal for "this position has no
 * meaningful body of its own" turned out to be two different things, not one.
 *
 * <p>Dispatch over the subtype set itself (once a type-ref is present and doesn't name the
 * declaration) is unchanged from before -- lazy, resolving a subtype's own parser only once it's
 * actually dispatched to (a subtype set is alternatives, not a fixed group every one of which is
 * needed on every read, unlike {@link RecordParser}'s own fields) -- factored into {@link
 * NamedDispatchParser}, shared with {@link ChoiceParser}'s own closed-list dispatch.
 */
final class VariantParser implements TsonTypeParser<Object> {

    static TsonTypeParser<?> forSubtypes(String name, TypeDefinition definition, TsonTypeParser<?> ownParser,
                                          CompilationContext ctx) {
        NamedDispatchParser subtypeDispatch = new NamedDispatchParser(name,
                "has known subtypes -- a value at this position with an explicit type annotation (!typeName) "
                        + "must name one of them",
                "known subtype", Set.copyOf(definition.subtypes()), ctx);
        return new VariantParser(name, ownParser, subtypeDispatch);
    }

    private final String name;
    private final TsonTypeParser<?> ownParser;
    private final TsonTypeParser<?> subtypeDispatch;

    private VariantParser(String name, TsonTypeParser<?> ownParser, TsonTypeParser<?> subtypeDispatch) {
        this.name = name;
        this.ownParser = ownParser;
        this.subtypeDispatch = subtypeDispatch;
    }

    @Override
    public Object read(DataValue value) {
        if (value == null || value.typeRef().isEmpty() || value.typeRef().get().equals(name)) {
            return ownParser.read(value);
        }
        return subtypeDispatch.read(value);
    }
}
