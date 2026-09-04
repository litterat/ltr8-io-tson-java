package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The {@code schema.meta} reference walk: every {@link TypeRef} an entry holds, rewritten or merely visited.
 *
 * <p><b>One walk, four callers, and none of them is about templates.</b> {@code TemplateMaterialiser} closes
 * an application through it,
 * {@code SyntheticMerge} renames onto a merged entry (§8.2), and {@code TemplateRegularity} uses it as a
 * visitor by returning each reference unchanged. Which body shape carries which references is a fact about
 * the value model, not about any of those four, so it is stated once here.
 *
 * <p><b>Visiting is rewriting with the identity function</b>, deliberately: a reader that walked bodies
 * separately would be a second list of shapes to keep in step, and the one that fell behind would silently
 * skip a reference rather than fail.
 */
final class MetaRefs {

    private MetaRefs() {
    }

    /**
     * Every {@link TypeRef} a definition holds, mapped -- {@code source}, and whatever its body carries.
     *
     * <p>{@code supertypes} is a name list rather than a type-ref channel and is deliberately not
     * covered: a composition operand is a named reference or an application (§5.7, §5.8), so a supertype
     * names a declared or an <em>instantiation</em> entry, never a synthetic one.
     */
    static TypeDefinition mapRefs(TypeDefinition definition, UnaryOperator<TypeRef> map) {
        Optional<TypeRef> source = definition.source().map(map);
        return new TypeDefinition(source, definition.kind(), definition.parameters(),
                definition.constructor(), definition.supertypes(), definition.subtypes(),
                definition.disjoint(), mapBodyRefs(definition.body(), map), definition.position(),
                definition.annotations());
    }

    /**
     * Every {@link TypeRef} one body holds, mapped -- <b>the only place that knows what each body shape
     * carries</b>, so a shape added to {@code schema.meta} needs remembering here and nowhere else.
     */
    static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map) {
        return switch (body) {
            case RecordBody record -> new RecordBody(record.supertypes(),
                    record.fields().stream().map(field -> field.withType(map.apply(field.type()))).toList(),
                    record.groups());
            case ArrayBody array -> new ArrayBody(map.apply(array.elementType()), array.state(),
                    array.unordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody mapBody -> new MapBody(map.apply(mapBody.keyType()), map.apply(mapBody.valueType()),
                    mapBody.state(), mapBody.minItems(), mapBody.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.elements().stream()
                    .map(element -> new TupleElement(map.apply(element.elementType()), element.state())).toList());
            case ChoiceBody choice -> new ChoiceBody(choice.variants().stream().map(map).toList());
            // An alias's target maps like any other reference, arguments and all -- which is what lets a
            // closed alias follow its own `source` onto the entry materialisation minted for it, and a
            // partial application keep the arguments it binds.
            case Reference reference -> new Reference(map.apply(reference.target()));
            // A held body maps nothing: its references are tokens that have not been resolved against
            // anything yet, and rewriting one would be rewriting a name whose meaning is not settled until
            // substitution supplies the arguments.
            default -> body; // an atom body holds no type references
        };
    }
}
