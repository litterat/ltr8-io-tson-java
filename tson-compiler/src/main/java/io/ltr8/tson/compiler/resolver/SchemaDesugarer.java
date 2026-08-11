package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.schema.ArrayContainerDef;
import io.ltr8.tson.compiler.ast.schema.ChoiceRef;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.ContainerDef;
import io.ltr8.tson.compiler.ast.schema.ContainerTypeDef;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.InlineArrayRef;
import io.ltr8.tson.compiler.ast.schema.InlineTupleRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.StructuralDef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleContainerDef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Rewrites a parsed {@link SchemaDocument} into an equivalent one with the sugar forms expanded, before
 * anything resolves it -- the {@code desugar} step of parse -&gt; desugar -&gt; resolve -&gt; link.
 *
 * <p><b>Why a phase rather than work inside the resolver or linker.</b> [TSON-SCHEMA] §5.3/§5.6 describe
 * {@code [T]}, {@code [T; N..M]}, {@code (A | B)} and {@code head&lt;args&gt;} as <em>desugarings</em>, and
 * §3.3.1 calls their targets "the implicit desugar targets of the sugar forms". Doing that expansion once, on
 * the AST, leaves exactly two forms for {@code DefinitionResolver} to handle -- a bare reference and {@code
 * !C value} -- and it already handles the second generically through the governing meta's compiled reader.
 * The alternative, which this replaces, split the same construct across two phases by position: a
 * declaration-position application became a real body in the resolver, while a field-position one was
 * deferred to {@code TsonSchemaLinker}, which sits in a module that cannot reach that generic machinery and
 * so needed a hand-written assembler per constructor shape.
 *
 * <p><b>Structural sharing is the core discipline.</b> Every method returns its input unchanged when nothing
 * beneath it changed, so an untouched document comes back as the <em>same object graph</em>, not an equal
 * copy. That matters for more than allocation: source positions are held in identity-keyed side tables
 * ({@code TsonSchemaParser.declarationPositions()} is an {@code IdentityHashMap}), so a rebuilt node silently
 * loses its position. Sharing confines that to the declarations actually containing sugar, rather than
 * imposing it on every declaration in every document.
 *
 * <p><b>Scope of the walk.</b> It visits every position the grammar allows a {@code TypeRef} to occur -- a
 * field's type, a group member's type, a composition supertype, a refinement source, a type argument, a
 * choice variant, an inline array/tuple element, and a declaration-level container's element, recursing
 * through nested containers. It deliberately does <em>not</em> descend into {@code Instance}/{@code
 * AtomRefinement} payloads or annotation values: those carry type <em>names</em> as bare strings inside a
 * {@code DataValue}, never a {@code TypeRef}, so no sugar can appear there and passing them through by
 * reference is both correct and position-preserving.
 */
final class SchemaDesugarer {

    private SchemaDesugarer() {
    }

    /** The whole document, or the same instance when it contains no sugar at all. */
    static SchemaDocument desugar(SchemaDocument document) {
        SchemaMap body = schemaMap(document.body());
        return body == document.body() ? document
                : new SchemaDocument(document.id(), document.meta(), document.imports(), body);
    }

    private static SchemaMap schemaMap(SchemaMap map) {
        Map<String, SchemaMap.Declaration> rewritten = null;
        for (Map.Entry<String, SchemaMap.Declaration> entry : map.declarations().entrySet()) {
            SchemaMap.Declaration declaration = declaration(entry.getValue());
            if (declaration != entry.getValue() && rewritten == null) {
                rewritten = new LinkedHashMap<>(map.declarations());
            }
            if (rewritten != null) {
                rewritten.put(entry.getKey(), declaration);
            }
        }
        return rewritten == null ? map : new SchemaMap(map.annotations(), rewritten);
    }

    private static SchemaMap.Declaration declaration(SchemaMap.Declaration declaration) {
        TypeDef typeDef = typeDef(declaration.typeDef());
        return typeDef == declaration.typeDef() ? declaration
                : new SchemaMap.Declaration(declaration.nameAnnotations(), declaration.name(),
                        declaration.typeDefAnnotations(), typeDef);
    }

    /**
     * {@code AtomRefinement} and {@code Instance} are passed through: their target is a bare {@code String}
     * type name and their payload is a {@code DataValue}, so neither can carry a sugar form.
     */
    private static TypeDef typeDef(TypeDef typeDef) {
        return switch (typeDef) {
            case StructuralTypeDef structural -> {
                StructuralDef body = structuralDef(structural.body());
                yield body == structural.body() ? structural
                        : new StructuralTypeDef(structural.typeParams(), structural.constructor(), body);
            }
            case ContainerTypeDef container -> {
                ContainerDef def = containerDef(container.container());
                yield def == container.container() ? container
                        : new ContainerTypeDef(container.typeParams(), def);
            }
            case ReferenceTypeDef reference -> {
                TypeRef ref = typeRef(reference.ref());
                yield ref == reference.ref() ? reference : new ReferenceTypeDef(reference.typeParams(), ref);
            }
            default -> typeDef;
        };
    }

    private static StructuralDef structuralDef(StructuralDef def) {
        return switch (def) {
            case RecordDef record -> recordDef(record);
            case RefinedDef refined -> {
                TypeRef target = typeRef(refined.target());
                RecordDef body = recordDef(refined.body());
                yield target == refined.target() && body == refined.body() ? refined
                        : new RefinedDef(target, body);
            }
            case ConstructionDef construction -> {
                List<TypeRef> supertypes = mapShared(construction.supertypes(), SchemaDesugarer::typeRef);
                var body = construction.body().map(SchemaDesugarer::recordDef);
                boolean bodyChanged = construction.body().isPresent()
                        && body.orElseThrow() != construction.body().orElseThrow();
                yield supertypes == construction.supertypes() && !bodyChanged ? construction
                        : new ConstructionDef(supertypes, body, construction.removal());
            }
        };
    }

    private static RecordDef recordDef(RecordDef record) {
        List<RecordEntry> entries = mapShared(record.entries(), SchemaDesugarer::recordEntry);
        return entries == record.entries() ? record : new RecordDef(entries);
    }

    private static RecordEntry recordEntry(RecordEntry entry) {
        return switch (entry) {
            case FieldDef field -> {
                if (field.type().isEmpty()) {
                    yield field; // modifier-only entry (a tightening body restating state, §5.7)
                }
                FieldDef.FieldType fieldType = field.type().orElseThrow();
                TypeRef ref = typeRef(fieldType.typeRef());
                yield ref == fieldType.typeRef() ? field
                        : new FieldDef(field.annotations(), field.name(),
                                java.util.Optional.of(new FieldDef.FieldType(ref, fieldType.optional())),
                                field.modifier());
            }
            case GroupDef group -> {
                List<GroupDef.Member> members = mapShared(group.members(), SchemaDesugarer::groupMember);
                yield members == group.members() ? group
                        : new GroupDef(group.annotations(), members, group.optional());
            }
        };
    }

    private static GroupDef.Member groupMember(GroupDef.Member member) {
        TypeRef ref = typeRef(member.typeRef());
        return ref == member.typeRef() ? member
                : new GroupDef.Member(member.annotations(), member.name(), ref);
    }

    private static ContainerDef containerDef(ContainerDef def) {
        return switch (def) {
            case ArrayContainerDef array -> {
                ElementType element = elementType(array.elementType());
                yield element == array.elementType() ? array : new ArrayContainerDef(element, array.size());
            }
            case TupleContainerDef tuple -> {
                List<ElementType> elements = mapShared(tuple.elementTypes(), SchemaDesugarer::elementType);
                yield elements == tuple.elementTypes() ? tuple : new TupleContainerDef(elements);
            }
        };
    }

    private static ElementType elementType(ElementType element) {
        return switch (element.expr()) {
            case ElementType.Expr.Plain plain -> {
                TypeRef ref = typeRef(plain.typeRef());
                yield ref == plain.typeRef() ? element
                        : new ElementType(new ElementType.Expr.Plain(ref), element.optional());
            }
            case ElementType.Expr.Nested nested -> {
                ContainerDef def = containerDef(nested.container());
                yield def == nested.container() ? element
                        : new ElementType(new ElementType.Expr.Nested(def), element.optional());
            }
        };
    }

    /**
     * The one method later stages replace: today every alternative is rebuilt only if a child changed, and
     * no child ever does, so every reference comes back as itself.
     */
    private static TypeRef typeRef(TypeRef ref) {
        return switch (ref) {
            case SimpleRef simple -> simple;
            case GenericRef generic -> {
                List<TypeArg> args = mapShared(generic.args(), SchemaDesugarer::typeArg);
                yield args == generic.args() ? generic : new GenericRef(generic.name(), args);
            }
            case InlineArrayRef array -> {
                TypeRef element = typeRef(array.elementType());
                yield element == array.elementType() ? array : new InlineArrayRef(element);
            }
            case InlineTupleRef tuple -> {
                List<TypeRef> elements = mapShared(tuple.elementTypes(), SchemaDesugarer::typeRef);
                yield elements == tuple.elementTypes() ? tuple : new InlineTupleRef(elements);
            }
            case ChoiceRef choice -> {
                List<TypeRef> variants = mapShared(choice.variants(), SchemaDesugarer::typeRef);
                yield variants == choice.variants() ? choice : new ChoiceRef(variants);
            }
        };
    }

    /** A {@code TypeArg.Value} is a literal token -- no type reference, nothing to rewrite. */
    private static TypeArg typeArg(TypeArg arg) {
        if (!(arg instanceof TypeArg.Ref ref)) {
            return arg;
        }
        TypeRef rewritten = typeRef(ref.ref());
        return rewritten == ref.ref() ? arg : new TypeArg.Ref(rewritten);
    }

    /**
     * Maps {@code items}, returning the original list when every element came back identical. The
     * reference-equality check is the point: it is what lets an unchanged subtree propagate "nothing changed"
     * all the way up rather than rebuilding every ancestor.
     */
    private static <T> List<T> mapShared(List<T> items, UnaryOperator<T> rewrite) {
        List<T> rewritten = null;
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            T mapped = rewrite.apply(item);
            if (mapped != item && rewritten == null) {
                rewritten = new ArrayList<>(items);
            }
            if (rewritten != null) {
                rewritten.set(i, mapped);
            }
        }
        return rewritten == null ? items : rewritten;
    }
}
