package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.ApplicationRef;
import io.ltr8.tson.compiler.ast.schema.ArrayExpr;
import io.ltr8.tson.compiler.ast.schema.ChoiceExpr;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.DependentType;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.FieldTypeExpr;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.InlineConstruction;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.MapExpr;
import io.ltr8.tson.compiler.ast.schema.NameRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;
import io.ltr8.tson.compiler.ast.schema.RecordExpr;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SizeSpec;
import io.ltr8.tson.compiler.ast.schema.StructuralDef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleExpr;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeExpr;
import io.ltr8.tson.compiler.ast.schema.TypeRef;
import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldRole;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Expands the schema sugar forms into the constructor applications they denote before anything resolves them
 * -- the {@code desugar} step of parse -&gt; desugar -&gt; resolve -&gt; link.
 *
 * <p><b>Why a phase rather than work inside the resolver or linker.</b> [TSON-SCHEMA] §5.3/§5.6 describe
 * {@code [T]} and {@code {K =&gt; V}} as <em>desugarings</em>, and §3.3.1 calls their targets "the implicit
 * desugar targets of the sugar forms". Doing that expansion once, on the AST, leaves {@code
 * DefinitionResolver} with a bare reference, an application or a {@code !C value} construction -- and it reads the
 * last generically through the constructor's compiled reader, at a declaration's body and at a field's type alike.
 *
 * <p><b>Purely syntactic, and per declaration.</b> The sugar set is closed and grammar-supplied, so the
 * head each form desugars to and the vocabulary field each argument fills are a fixed table (§5.3):
 *
 * <pre>
 * [T]              !meta.array { element_type: T }
 * [T; N..M]        !meta.array { element_type: T  min_items: N  max_items: M }
 * [T?; ...]        the corresponding form with voidable: true bound directly
 * [T, U]           !meta.tuple { elements: [{ element_type: T } { element_type: U }] }
 * (A | B)          !meta.choice { variants: [A B] }
 * {K =&gt; V}         !meta.map   { key_type: K  value_type: V }
 * {K =&gt; V; N..M}   the same, with min_items/max_items
 * { a: T  b?: U }  !meta.record { fields: { a =&gt; { type: T }  b =&gt; { type: U  optional: true } } }
 * kind!            !meta.dependent { on: kind }, at a field's type alone
 * </pre>
 *
 * This phase therefore consults no governing meta at all. The kernel's container constructors are
 * parameterless (§4.2), so nothing about routing is stated in a meta to be read off it: the table above is
 * the whole rule, and the kernel's own bootstrap needs no special case.
 *
 * <p><b>A nested bracket or brace form expands innermost first.</b> §5.3's declaration-level container
 * syntax nests inside itself -- {@code [[T; N]; N]}, {@code {text =&gt; [order; 1..]}} -- so a position
 * holding one is desugared first, and the enclosing construction writes it in place, in its {@code { of: !C { ... }
 * }} wrapper, at any depth. No declaration is added. See {@link #elementRef} and {@link #slotValue}.
 *
 * <p><b>An application passes through.</b> {@code name&lt;args&gt;} resolves its head in the schema's
 * namespace -- parameters, then locals, then imports -- and is a §5.10 template application or, over a
 * constructor, construction sugar (§5.3); {@code TemplateMaterialiser} closes either over the <em>resolved</em>
 * form one phase later. What is checked here is the one thing an AST alone decides: a head this document
 * declares with no parameters, and with a body no constructor has, takes no arguments at all. See {@link
 * #checkTemplateApplication}.
 *
 * <p><b>Structural sharing.</b> Every method returns its input unchanged when nothing beneath it changed, so
 * a document with no sugar comes back as the same object graph. Source positions live in identity-keyed side
 * tables ({@code TsonSchemaParser.declarationPositions()} is an {@code IdentityHashMap}), so a rebuilt node
 * silently loses its position; sharing confines that to the declarations that genuinely contain sugar.
 *
 * <p><b>What is deliberately left alone.</b> Three positions keep their heads intact, because a name there is
 * being <em>declared</em> or <em>composed</em>, not applied: a declaration's own body reference
 * ({@code ReferenceTypeDef}), a refinement source, and a composition supertype.
 *
 * <p><b>A template's body is a record body</b> ([TSON-SCHEMA] §5.10): braces, a composition or a refinement, and the
 * record passes through as it would without parameters, for {@code DefinitionResolver} to build as an open record. A
 * parameter list before a sugar form or a bare reference -- {@code <T> [T]}, {@code <T> ( T | error )}, {@code <B>
 * pair<uuid, B>} -- is refused here, naming what to write instead ({@link #refuseTemplateBody}). Inside an open record
 * a form is its construction in place whether or not it names a parameter: a parameter is a name at a type slot like
 * any other, which the open record scopes and each application substitutes.
 *
 * <p><b>An invalid sugar form is reported per declaration, not thrown</b>, when a {@link
 * DesugarFailureReporter} is supplied -- the same one-pass treatment {@code SchemaResolver} and {@code
 * TsonSchemaLinker} give their own phases, so an author sees every independent problem at once rather than
 * one per run. {@link #desugarOrReport} has the mechanics and {@link #absorbed} what a reported declaration
 * leaves behind.
 */
final class SchemaDesugarer {

    /** §5.3's desugar target for {@code [T]} and the sized forms, fixed by the sugar rather than by the author. */
    private static final String ARRAY = KernelNames.of("array");

    /** §5.3's desugar target for <code>{K =&gt; V}</code>, fixed the same way {@link #ARRAY} is. */
    private static final String MAP = KernelNames.of("map");

    /** §5.3's desugar target for {@code [T, U]}. */
    private static final String TUPLE = KernelNames.of("tuple");

    /** §5.4's desugar target for {@code (A | B)}. */
    private static final String CHOICE = KernelNames.of("choice");

    /** §5.2's desugar target for {@code kind!} at a field's type. */
    private static final String DEPENDENT = KernelNames.of("dependent");

    /** §5.2's desugar target for a record body at a type slot. */
    private static final String RECORD = WireForm.RECORD;


    /** The vocabulary fields the desugar table binds -- fixed by the table, not looked up in a governing meta. */
    private static final String ELEMENT_TYPE = "element_type";
    private static final String KEY_TYPE = "key_type";
    private static final String VALUE_TYPE = "value_type";
    private static final String MIN_ITEMS = "min_items";
    private static final String MAX_ITEMS = "max_items";
    private static final String ELEMENTS = "elements";
    private static final String VARIANTS = "variants";
    private static final String OPTIONAL = "optional";
    private static final String ROLE = "role";
    private static final String GROUPS = "groups";
    private static final String MEMBERS = "members";
    private static final String OPTIONAL_MEMBERS = "optional_members";
    private static final String DISCRIMINATORS = "discriminators";
    private static final String EXTENSION = "extension";

    /** {@code meta.type_inline.of}: the wrapper a nested construction takes at a type slot. */
    private static final String OF = "of";


    /**
     * What a declaration whose sugar form was reported is replaced with: a fresh, zero-field record. The
     * counterpart of {@code SchemaResolver.unresolved} one phase later, and the same javac model -- an error
     * type that answers every question, rather than Swift's, where every questioner must first check whether
     * it is looking at one. A dependent that composes with a failed declaration resolves cleanly, contributing
     * no fields, instead of failing a second time over a problem that is purely a consequence of the first.
     *
     * <p><b>Producing one means a diagnostic has already been reported</b> -- Swift's {@code ErrorType}
     * obligation, and the half of its model worth keeping. It can only ever be reached through a {@link
     * DesugarFailureReporter}, so a document that expanded cleanly can never contain one.
     *
     * <p><b>It keeps the declaration's own type parameters</b>, and that is the one declaration-specific
     * thing it carries. Answering "how many type parameters?" with zero makes a downstream {@code bl<text>}
     * report that {@code bl} "declares no type parameters ... drop the argument list" -- advice that is
     * wrong, since the fix is upstream at the declaration that actually failed. Absorbing means answering
     * every question, not answering them all with nothing.
     *
     * <p>It is deliberately never in {@code TsonSchemaParser.declarationPositions()} -- the position belongs
     * to the diagnostic already reported against the real declaration, not to this stand-in.
     *
     * <p><b>A stand-in keeping parameters is an open empty record</b>, an open record like any other where
     * {@code DefinitionResolver} resolves it, so {@code TemplateMaterialiser} closes it by the one process.
     */
    private static TypeDef absorbed(SchemaMap.Declaration declaration) {
        return new StructuralTypeDef(typeParams(declaration.typeDef()), new RecordDef(List.of()));
    }

    /** This document's own declarations, for {@link #checkTemplateApplication} -- set before the walk starts. */
    private Map<String, SchemaMap.Declaration> local = Map.of();

    /** The type parameters of the declaration currently being walked, or empty outside a template. */
    private List<String> currentParameters = List.of();

    /** The name of the declaration currently being walked, for a refusal that names it. */
    private String currentName = "";

    /** Where an invalid sugar form is reported, or {@code null} to rethrow it and abandon the document. */
    private final DesugarFailureReporter reporter;

    /**
     * The identity-keyed declaration positions, carried across a rewrite -- see {@link #schemaMap}. Never
     * {@code null}; a caller with no positions to keep passes an empty map it then discards.
     */
    private final SchemaPositions positions;

    private SchemaDesugarer(DesugarFailureReporter reporter, SchemaPositions positions) {
        this.reporter = reporter;
        this.positions = positions;
    }

    /**
     * {@link #desugar(SchemaDocument, DesugarFailureReporter, SchemaPositions)}, throwing at the first invalid sugar
     * form and keeping no positions -- a caller with no source text behind the document.
     */
    static SchemaDocument desugar(SchemaDocument document) {
        return desugar(document, null, SchemaPositions.none());
    }

    /**
     * The document with every sugar form rewritten in place into the construction it denotes, or the same instance
     * when there was nothing to rewrite. No declaration is added: the declarations out are the declarations in.
     *
     * <p>A declaration whose sugar form is invalid is reported to {@code reporter} and replaced with {@link
     * #absorbed}, so the declarations around it still expand and go on to resolve -- see {@link
     * #desugarOrReport}. With a {@code null} {@code reporter} the first such form throws instead.
     */
    static SchemaDocument desugar(SchemaDocument document, DesugarFailureReporter reporter,
            SchemaPositions positions) {
        SchemaDesugarer pass = new SchemaDesugarer(reporter, positions);
        pass.local = document.body().declarations();
        SchemaMap body = pass.schemaMap(document.body());
        if (body == document.body()) {
            return document;
        }
        return new SchemaDocument(document.id(), document.imports(), body);
    }

    /**
     * <b>The one place a rewritten declaration replaces an original</b>, and so the one place the original's
     * source position has to be carried over. {@code positions} is identity-keyed (see {@code
     * TsonSchemaParser.declarationPositions()}), which is what makes structural sharing load-bearing
     * everywhere else -- but a declaration that genuinely contains sugar <em>is</em> rebuilt, and without
     * this every diagnostic against it would lose its line. That is not a rare case: any record with a single
     * {@code [T]} field is rewritten whole.
     */
    private SchemaMap schemaMap(SchemaMap map) {
        Map<String, SchemaMap.Declaration> rewritten = null;
        for (Map.Entry<String, SchemaMap.Declaration> entry : map.declarations().entrySet()) {
            SchemaMap.Declaration declaration = desugarOrReport(entry.getValue());
            if (declaration != entry.getValue()) {
                SourcePosition position = positions.declarations().get(entry.getValue());
                if (position != null) {
                    positions.declarations().put(declaration, position);
                }
                if (rewritten == null) {
                    rewritten = new LinkedHashMap<>(map.declarations());
                }
            }
            if (rewritten != null) {
                rewritten.put(entry.getKey(), declaration);
            }
        }
        return rewritten == null ? map : new SchemaMap(map.annotations(), rewritten);
    }

    /**
     * One declaration expanded, or -- when its sugar form is invalid and a {@link DesugarFailureReporter} is
     * in play -- reported and replaced with {@link #absorbed}. Per declaration, which is both the granularity
     * {@code SchemaResolver} reports at one phase later and the finest source positions this project has.
     *
     * <p><b>The substitution is not optional.</b> Leaving the declaration un-expanded would hand {@code
     * DefinitionResolver} the very {@code ContainerTypeDef} this phase exists to remove, and it answers that
     * with an {@code UnsupportedOperationException}, which {@code SchemaResolver} reports as {@code
     * NOT_IMPLEMENTED} -- a gap in this library, not a verdict on the author's schema. So passing through would
     * turn the author's error into a claimed library gap: the wrong diagnosis, and the wrong CLI exit code.
     *
     * <p><b>A gap is reported too, and absorbed the same way</b> -- as {@code Diagnostic.Code.NOT_IMPLEMENTED},
     * not as an author error. Thrown instead, it would take every other declaration's verdict with it: one
     * unimplemented construct and a document with three ordinary mistakes in it would report none of them.
     * The classification the exception policy draws is what picks the code, and does not decide whether the
     * pass survives. Fail-fast (a {@code null} reporter) rethrows the original exception untouched.
     */
    private SchemaMap.Declaration desugarOrReport(SchemaMap.Declaration declaration) {
        try {
            return declaration(declaration);
        } catch (SchemaValidationException | UnsupportedOperationException e) {
            if (reporter == null) {
                throw e;
            }
            reporter.reportFailedDeclaration(declaration, e);
            return declaration.withTypeDef(absorbed(declaration));
        }
    }

    private SchemaMap.Declaration declaration(SchemaMap.Declaration declaration) {
        currentParameters = typeParams(declaration.typeDef());
        currentName = declaration.name();
        try {
            TypeDef typeDef = typeDef(declaration.typeDef());
            return typeDef == declaration.typeDef() ? declaration : declaration.withTypeDef(typeDef);
        } finally {
            currentParameters = List.of();
            currentName = "";
        }
    }

    /**
     * {@code AtomRefinement} and {@code Instance} are passed through: their target is a bare {@code String}
     * type name and their payload is a {@code DataValue}, so neither can carry a sugar form. An {@code Instance}
     * with parameters is the resolver's to admit (before {@code !meta.record}) or refuse, since only it knows what
     * the head is.
     *
     * <p>A template's record body is walked as any record's; a parameter list before anything but a record body is
     * refused ({@link #refuseTemplateBody}).
     */
    private TypeDef typeDef(TypeDef typeDef) {
        return switch (typeDef) {
            case StructuralTypeDef structural -> {
                StructuralDef body = structuralDef(structural.body());
                yield body == structural.body() ? structural
                        : new StructuralTypeDef(structural.typeParams(), body);
            }
            // A declaration's own body reference names what this declaration *is*; only its arguments are
            // expandable, so the head stays put and its own handling is unchanged.
            case ReferenceTypeDef reference when !reference.typeParams().isEmpty() ->
                    throw refuseTemplateBody(reference.ref());
            case ReferenceTypeDef reference -> {
                // **A declaration's own body is the construction** (§5.3): the form becomes the instance
                // directly, which keeps `score_list => [integer; 1..]` a PRODUCT entry with a real body and
                // `contact => (email | phone)` a SUM entry with a real ChoiceBody. Every sugar form takes this
                // path -- the bracket forms reach it through `type-ref` like the rest, since there is no separate
                // declaration-level tier.
                Optional<Binding> binding = binding(reference.ref());
                if (binding.isPresent()) {
                    yield instance(binding.get());
                }
                TypeExpr ref = argumentsOnly(reference.ref());
                yield ref == reference.ref() ? reference : new ReferenceTypeDef(reference.typeParams(), ref);
            }
            default -> typeDef;
        };
    }

    /**
     * A parameter list before a body that is no record ([TSON-SCHEMA] §5.10: a template's body is a record body --
     * braces, a composition or a refinement), refused with what to write instead. A sugar form is a construction,
     * applied where it is used or declared as a refined constructor; a choice or a tuple is a discriminated record
     * family or a field group; a bare reference is an alias template, which names a partial application nothing
     * could close.
     */
    private SchemaValidationException refuseTemplateBody(TypeExpr body) {
        String prefix = "'" + currentName + "': a template's body is a record body -- braces, a composition or a "
                + "refinement (§5.10) -- and '" + spell(body) + "' is ";
        return new SchemaValidationException(prefix + switch (body) {
            case ArrayExpr _ -> "an array. Write the application where it is used ('meta.array<T>', '[T]'), or "
                    + "declare a refined constructor ('nonempty_type => meta.array ^ { min_items?: = 1 }') and apply it";
            case MapExpr _ -> "a map. Write the application where it is used ('meta.map<K V>', '{K => V}'), or "
                    + "declare a refined constructor ('bounded_map => meta.map ^ { max_items?: = 10 }') and apply it";
            case ChoiceExpr _ -> "a choice. Declare a discriminated record family instead (an abstract base with a "
                    + "selector, a template composing it, and members pinning it), or write the alternative as a "
                    + "field group inside a record, '( body: T | problem: problem )'";
            case TupleExpr _ -> "a tuple. Name the positions as the fields of a record template, '<A, B> { first: A "
                    + " second: B }', or declare the tuple for each type it holds";
            default -> "a reference, so the declaration would be an alias template, which a template's body never "
                    + "is. Restate the body as a record ('<B> { first: uuid  second: B }'), or write the application "
                    + "at each use ('pair<uuid, int32>')";
        });
    }

    /** A form as the author spells it, for a message. */
    static String spell(TypeExpr ref) {
        return switch (ref) {
            case NameRef simple -> simple.name();
            case ApplicationRef generic -> generic.name() + "<" + String.join(", ",
                    generic.args().stream().map(SchemaDesugarer::spell).toList()) + ">";
            case ArrayExpr array -> "[" + spell(array.elementType()) + array.size().map(SchemaDesugarer::spell)
                    .orElse("") + "]";
            case MapExpr map -> "{" + spell(map.keyType()) + " => " + spell(map.valueType())
                    + map.size().map(SchemaDesugarer::spell).orElse("") + "}";
            case TupleExpr tuple -> "[" + String.join(", ",
                    tuple.elementTypes().stream().map(SchemaDesugarer::spell).toList()) + "]";
            case ChoiceExpr choice -> "( " + String.join(" | ",
                    choice.variants().stream().map(SchemaDesugarer::spell).toList()) + " )";
            case RecordExpr record -> spell(record.body());
            case InlineConstruction inline -> "!" + inline.constructor() + " { ... }";
        };
    }

    /** A record body as the author spells it, each field's marks and type, a group's options. */
    private static String spell(RecordDef record) {
        List<String> entries = new ArrayList<>();
        for (RecordEntry entry : record.entries()) {
            entries.add(switch (entry) {
                case FieldDef field -> field.name() + (field.omittable() ? "?" : "") + ":" + field.type()
                        .map(type -> " " + spell(type.typeRef()) + (type.voidable() ? "?" : "")).orElse("")
                        + field.modifier().map(SchemaDesugarer::spell).orElse("");
                case GroupDef group -> "( " + String.join(" | ", group.options().stream().map(option -> String.join(
                        "  ", option.stream().map(member -> member.name() + (member.omittable() ? "?" : "") + ": "
                                + spell(member.typeRef()) + (member.voidable() ? "?" : "")).toList())).toList())
                        + " )" + switch (group.quantifier()) {
                            case AT_MOST_ONE -> "?";
                            case AT_LEAST_ONE -> "+";
                            case EXACTLY_ONE -> "";
                        };
            });
        }
        return entries.isEmpty() ? "{}" : "{ " + String.join("  ", entries) + " }";
    }

    private static String spell(FieldTypeExpr type) {
        return type instanceof DependentType dependent ? dependent.on() + "!" : spell((TypeExpr) type);
    }

    private static String spell(FieldDef.Modifier modifier) {
        return (modifier.kind() == FieldDef.Modifier.Kind.DEFAULT ? " ~ " : " = ") + switch (modifier.value()) {
            case FieldDef.Modifier.Value.Deferred _ -> "?";
            case FieldDef.Modifier.Value.Absent _ -> "_";
            case FieldDef.Modifier.Value.Literal literal -> literal.token().text();
        };
    }

    private static String spell(ElementType element) {
        return spell(element.typeRef()) + (element.voidable() ? "?" : "");
    }

    private static String spell(SizeSpec size) {
        return "; " + switch (size) {
            case SizeSpec.Exact exact -> exact.bound();
            case SizeSpec.Ranged ranged -> ranged.lower() + ".." + ranged.upper();
            case SizeSpec.Min min -> min.lower() + "..";
            case SizeSpec.Max max -> ".." + max.upper();
        };
    }

    private StructuralDef structuralDef(StructuralDef def) {
        return switch (def) {
            case RecordDef record -> recordDef(record);
            case RefinedDef refined -> {
                // §5.7 refines a *named* source; only its arguments are expandable.
                TypeRef target = refined.target() instanceof ApplicationRef generic
                        ? applicationArguments(generic) : refined.target();
                RecordDef body = recordDef(refined.body());
                yield target == refined.target() && body == refined.body() ? refined
                        : new RefinedDef(target, body);
            }
            case ConstructionDef construction -> {
                // §5.8 composes with named supertypes; only their arguments are expandable.
                List<TypeExpr> supertypes = mapShared(construction.supertypes(), this::argumentsOnly);
                Optional<RecordDef> body = construction.body().map(this::recordDef);
                boolean bodyChanged = construction.body().isPresent()
                        && body.orElseThrow() != construction.body().orElseThrow();
                yield supertypes == construction.supertypes() && !bodyChanged ? construction
                        : new ConstructionDef(supertypes, body, construction.removal());
            }
        };
    }

    private RecordDef recordDef(RecordDef record) {
        List<RecordEntry> entries = mapShared(record.entries(), this::recordEntry);
        return entries == record.entries() ? record : new RecordDef(entries);
    }

    private RecordEntry recordEntry(RecordEntry entry) {
        return switch (entry) {
            case FieldDef field -> {
                if (field.type().isEmpty()) {
                    yield field; // modifier-only entry (a tightening body restating state, §5.7)
                }
                FieldDef.FieldType fieldType = field.type().orElseThrow();
                TypeExpr ref = fieldType(fieldType.typeRef());
                if (ref == fieldType.typeRef()) {
                    yield field;
                }
                // The field's own position has to be carried, for schemaMap's reason one level down: a field
                // whose type is a sugar form is rebuilt, and a rebuilt node is a different identity in a
                // table keyed by one. Any record with a single `[T]` field hits this.
                FieldDef rewritten = new FieldDef(field.annotations(), field.name(), field.omittable(),
                        Optional.of(new FieldDef.FieldType(ref, fieldType.voidable())), field.modifier());
                positions.carry(field, rewritten);
                yield rewritten;
            }
            case GroupDef group -> {
                List<List<GroupDef.Member>> options = mapShared(group.options(),
                        option -> mapShared(option, this::groupMember));
                yield options == group.options() ? group
                        : new GroupDef(group.annotations(), options, group.quantifier());
            }
        };
    }

    private GroupDef.Member groupMember(GroupDef.Member member) {
        TypeExpr ref = fieldType(member.typeRef());
        return ref == member.typeRef() ? member
                : new GroupDef.Member(member.annotations(), member.name(), member.omittable(), ref,
                        member.voidable());
    }

    /** A field's or group member's type: {@code kind!} is its construction in place, any other as {@link #typeRef}. */
    private TypeExpr fieldType(FieldTypeExpr type) {
        return switch (type) {
            case DependentType dependent -> new InlineConstruction(construction(new Binding(DEPENDENT,
                    List.of(WireForm.nameField("on", dependent.on())))));
            case TypeExpr ref -> typeRef(ref);
        };
    }

    /**
     * A type at a field's or group member's slot, where a sugar form is written in place: children first, so a
     * nested form is already its construction by the time the enclosing one is built, and lands in the enclosing
     * binding as a {@code { of: … }} wrapper ({@code meta.type_inline}).
     */
    private TypeExpr typeRef(TypeExpr ref) {
        return switch (ref) {
            case NameRef simple -> simple;
            case ArrayExpr array -> container(ref, List.of(elementRef(array.elementType())));
            case MapExpr map -> container(ref, List.of(typeRef(map.keyType()), elementRef(map.valueType())));
            case TupleExpr tuple -> container(ref, tuple.elementTypes().stream().map(this::elementRef).toList());
            case ChoiceExpr choice -> container(ref, mapShared(choice.variants(), this::typeRef));
            case RecordExpr record -> recordInPlace(record);
            case ApplicationRef generic -> applicationArguments(generic);
            case InlineConstruction inline -> inline;
        };
    }

    /**
     * A record body at a type slot, written in place as the {@code meta.record} construction it denotes, its own
     * forms already theirs ({@link #recordDef}).
     */
    private TypeExpr recordInPlace(RecordExpr written) {
        return new InlineConstruction(construction(recordBinding(recordDef(written.body()))));
    }

    /** A sugar form whose positions are {@code children}, already rewritten, as the construction it denotes in place. */
    private TypeExpr container(TypeExpr ref, List<TypeExpr> children) {
        Binding binding = binding(ref, children);
        refuseParameterBound(binding);
        return new InlineConstruction(construction(binding));
    }

    /** Expands only within a reference's arguments, leaving its own head in place. */
    private TypeExpr argumentsOnly(TypeExpr ref) {
        return ref instanceof ApplicationRef generic ? applicationArguments(generic) : ref;
    }

    /**
     * {@code generic} with its arguments expanded, the same node where none changes. An argument is a name or a
     * parameter ([TSON-SCHEMA] §5.10), so a sugar form as one is refused, naming what to declare.
     */
    private ApplicationRef applicationArguments(ApplicationRef generic) {
        checkTemplateApplication(generic.name());
        for (TypeExpr arg : generic.args()) {
            if (arg instanceof RecordExpr) {
                throw constructionAsArgument(generic, arg);
            }
        }
        List<TypeExpr> args = mapShared(generic.args(), this::typeRef);
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) instanceof InlineConstruction) {
                throw constructionAsArgument(generic, generic.args().get(i));
            }
        }
        return args == generic.args() ? generic : new ApplicationRef(generic.name(), args);
    }

    /** {@code written}, a sugar form, standing as an argument of {@code generic}: refused, to be declared first. */
    private SchemaValidationException constructionAsArgument(ApplicationRef generic, TypeExpr written) {
        return new SchemaValidationException("'" + currentName + "': '" + spell(written) + "' is a construction "
                + "passed as an argument of '" + generic.name() + "<...>', and an argument is a name or a parameter "
                + "(§5.10): declare it under a name ('my_type => " + spell(written) + "') and pass that name");
    }

    /**
     * Checks a generic application, which after §3.3.1's type-name-only head resolution can only ever be a
     * §5.10 user-template application: {@code box => <T> { v: T }} applied as {@code box<text>}.
     *
     * <p><b>An open record passes through</b> -- substitution happens over the <em>resolved</em> open record, not
     * over the AST, so this phase leaves the application for {@code SchemaResolver} to materialise and the head keeps
     * its arguments into resolution.
     *
     * <p>A head this document neither declares nor imports is left alone -- the reference is simply
     * unresolved, which is {@code TsonSchemaLinker}'s verdict to deliver. A local head that declares no
     * parameters is an author error where its body says it is no constructor: a fresh record IS-A nothing, and
     * a construction or atom refinement carries an empty chain (§4.1), so nothing there takes arguments. A
     * composition, a refinement or an alias may be a constructor, which construction sugar applies (§5.3), and
     * only resolution can tell, so each passes through.
     */
    private void checkTemplateApplication(String head) {
        SchemaMap.Declaration declaration = local.get(head);
        if (declaration == null) {
            return;
        }
        List<String> parameters = typeParams(declaration.typeDef());
        boolean mayConstruct = switch (declaration.typeDef()) {
            case StructuralTypeDef structural -> !(structural.body() instanceof RecordDef);
            case ReferenceTypeDef ignored -> true;
            default -> false;
        };
        if (parameters.isEmpty() && !mayConstruct) {
            throw new SchemaValidationException("'" + head + "' declares no type parameters, so '"
                    + head + "<...>' applies arguments to something that takes none (§5.10); drop the "
                    + "argument list");
        }
    }

    /** A declaration's declared type parameters, or none -- also how a placeholder learns its own arity. */
    static List<String> typeParams(TypeDef typeDef) {
        return switch (typeDef) {
            case Instance instance -> instance.typeParams();
            case StructuralTypeDef structural -> structural.typeParams();
            case ReferenceTypeDef reference -> reference.typeParams();
            default -> List.of();
        };
    }

    // ── The desugar table (§5.3): one sugar form, one binding record ─────────────────────────────

    /**
     * A sugar form reduced to what it denotes: a fixed constructor head and the vocabulary fields the form
     * binds, in the order the table above lists them. Everything downstream -- the emitted {@code !C { ... }}
     * and the bound-coherence check -- reads this and nothing else.
     */
    private record Binding(String head, List<RecordValue.Field> fields) {
    }

    /** One type slot as the wire field a construction writes -- see {@link #slotValue}. */
    private static void slot(String slot, TypeExpr ref, List<RecordValue.Field> fields) {
        fields.add(new RecordValue.Field(slot, WireForm.plain(slotValue(ref))));
    }

    /**
     * What a type slot holds in a binding record: a bare token for a plain name, an application as the data
     * grammar's reference, unquoted ({@link WireForm#refValue}), and a nested construction in the wrapper a type
     * written in place takes, {@code { of: !meta.array { … } }} ({@code meta.type_inline}), written untagged, the
     * slot's choice being disjoint by class ([TSON-SCHEMA] §5.4). One spelling per shape, produced in one place.
     */
    private static CoreValue slotValue(TypeExpr ref) {
        return switch (ref) {
            case InlineConstruction inline -> new RecordValue(List.of(
                    new RecordValue.Field(OF, inline.value())));
            default -> WireForm.refValue(reference(ref));
        };
    }

    /** A grammar-level reference as the reference value it spells: a name, or an application of references. */
    private static io.ltr8.tson.schema.meta.TypeRef reference(TypeExpr ref) {
        if (ref instanceof NameRef simple) {
            return io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
        }
        ApplicationRef generic = (ApplicationRef) ref;
        List<io.ltr8.tson.schema.meta.TypeRef> arguments = new ArrayList<>();
        for (TypeExpr argument : generic.args()) {
            arguments.add(reference(argument));
        }
        return io.ltr8.tson.schema.meta.TypeRef.of(generic.name(), arguments);
    }

    /** One position of a tuple after expansion: the type it holds, and whether it is marked voidable. */
    private record Position(TypeExpr typeRef, boolean voidable) {
    }

    /**
     * A declaration-level container as the binding record it denotes, or empty for a body that is no sugar form.
     * A nested form among its positions is the construction it denotes, in its wrapper.
     */
    private Optional<Binding> binding(TypeExpr ref) {
        return switch (ref) {
            case ArrayExpr array -> Optional.of(binding(ref, List.of(elementRef(array.elementType()))));
            case MapExpr map -> Optional.of(binding(ref,
                    List.of(typeRef(map.keyType()), elementRef(map.valueType()))));
            case TupleExpr tuple -> Optional.of(binding(ref,
                    tuple.elementTypes().stream().map(this::elementRef).toList()));
            case ChoiceExpr choice -> Optional.of(binding(ref, mapShared(choice.variants(), this::typeRef)));
            default -> Optional.empty();
        };
    }

    /** The binding record of the sugar form {@code ref} over its positions, {@code children}, already rewritten. */
    private Binding binding(TypeExpr ref, List<TypeExpr> children) {
        return switch (ref) {
            case ArrayExpr array -> arrayBinding(children.getFirst(), array.elementType().voidable(), array.size(),
                    shownElement(array.elementType()));
            case MapExpr map -> mapBinding(children.get(0), children.get(1), map.valueType().voidable(),
                    map.size());
            case TupleExpr tuple -> {
                List<Position> positions = new ArrayList<>();
                for (int i = 0; i < children.size(); i++) {
                    positions.add(new Position(children.get(i), tuple.elementTypes().get(i).voidable()));
                }
                yield tupleBinding(positions);
            }
            case ChoiceExpr _ -> choiceBinding(children);
            default -> throw new IllegalStateException("'" + spell(ref) + "' is no sugar form");
        };
    }

    /**
     * {@code !meta.array { element_type: T [voidable: true] [min_items: N] [max_items: M] }} -- the whole array
     * row of the desugar table, the unsized and sized spellings alike.
     *
     * <p><b>The element {@code ?} binds {@code voidable} directly</b>, alongside the bounds rather than through
     * them: §5.3's {@code [T?; 3]} states both at once and both land on the one record. An unmarked element
     * states nothing at all and lets §5.2's default injection supply it, exactly as an unmarked tuple
     * position omits its own {@code voidable}.
     */
    private static Binding arrayBinding(TypeExpr element, boolean voidable, Optional<SizeSpec> size,
            String shown) {
        List<RecordValue.Field> fields = new ArrayList<>();
        slot(ELEMENT_TYPE, element, fields);
        if (voidable) {
            fields.add(WireForm.nameField(WireForm.VOIDABLE, "true"));
        }
        size.ifPresent(spec -> fields.addAll(sizeFields(spec, "[" + shown + "; 0..]")));
        return new Binding(ARRAY, fields);
    }

    /**
     * <code>!meta.map { key_type: K  value_type: V [voidable: true] [min_items: N] [max_items: M] }</code> --
     * the map row of the desugar table. The <b>value</b> carries {@code voidable} the way an array element
     * does, written only when marked; the key carries none, a key never being void ([TSON-DATA] §2.9).
     *
     * <p><b>A key is a name</b> ([TSON-SCHEMA] §5.3): {@code key_type} is a {@code meta.type_ref}, and the grammar
     * admits no sugar form at a key, so a key is a name or an application, written through {@link #slotValue} as
     * every slot is.
     */
    private static Binding mapBinding(TypeExpr key, TypeExpr value, boolean voidable, Optional<SizeSpec> size) {
        List<RecordValue.Field> fields = new ArrayList<>();
        slot(KEY_TYPE, key, fields);
        slot(VALUE_TYPE, value, fields);
        if (voidable) {
            fields.add(WireForm.nameField(WireForm.VOIDABLE, "true"));
        }
        size.ifPresent(spec -> fields.addAll(
                sizeFields(spec, "{" + shownRef(key) + " => " + shownRef(value) + "; 0..}")));
        return new Binding(MAP, fields);
    }

    /** How a map side is quoted back in the one diagnostic that shows the form. */
    private static String shownRef(TypeExpr ref) {
        return switch (ref) {
            case NameRef simple -> simple.name();
            case ApplicationRef generic -> generic.name() + "<...>";
            default -> "T";
        };
    }

    /**
     * {@code !meta.tuple { elements: [{ element_type: T } { element_type: U }] }} -- the tuple row of the table.
     *
     * <p><b>Why this is not {@link #choiceBinding}.</b> Both are variadic and both fill one collection-typed
     * vocabulary field. What differs is what one position <em>is</em>: a variant is a type slot, while an element is
     * a {@code meta.tuple_element} record carrying a type <em>and</em> its own {@code voidable}, so each position
     * needs a record built for it. {@code voidable} is written only for a marked position, for the reason {@link
     * #arrayBinding} omits an unmarked element's. Both write a position's type through {@link #slotValue}.
     */
    private static Binding tupleBinding(List<Position> positions) {
        List<DataValue> elements = new ArrayList<>();
        for (Position position : positions) {
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(new RecordValue.Field(ELEMENT_TYPE, WireForm.plain(slotValue(position.typeRef()))));
            if (position.voidable()) {
                members.add(WireForm.nameField(WireForm.VOIDABLE, "true"));
            }
            elements.add(WireForm.plain(new RecordValue(members)));
        }
        return new Binding(TUPLE, List.of(new RecordValue.Field(ELEMENTS, WireForm.plain(new ArrayValue(elements)))));
    }

    /**
     * {@code !meta.choice { variants: [A B ...] }} -- the choice row of the table. A variant is a type slot like any
     * other ({@code variants: [meta.type_expr]}), written through {@link #slotValue}: a name, an application held
     * for materialisation to close, or a nested form in its wrapper.
     *
     * <p>Distinctness of the variants (§5.4: "the resolver validates that each variant resolves to a distinct
     * type") is deliberately not checked here -- it is a question about what the names <em>resolve</em> to,
     * after alias flattening, which this phase has no answer to.
     */
    private static Binding choiceBinding(List<TypeExpr> variants) {
        List<DataValue> members = new ArrayList<>();
        for (TypeExpr variant : variants) {
            members.add(WireForm.plain(slotValue(variant)));
        }
        return new Binding(CHOICE, List.of(new RecordValue.Field(VARIANTS, WireForm.plain(new ArrayValue(members)))));
    }

    /**
     * {@code !meta.record { fields: { … }  groups: [ … ]  discriminators: [ … ] }} -- the record row of the table, its
     * forms already written in place. Each field is the {@code meta.record_field} {@link FieldModifiers} makes of its
     * marks, a fact written only where it is no default; a group member is a field, optional, its presence the group's.
     * What the value cannot carry, its annotations and position, is kept against it ({@link
     * SchemaPositions.WrittenField}).
     */
    private Binding recordBinding(RecordDef record) {
        List<MapValue.MapEntry> fields = new ArrayList<>();
        List<DataValue> groups = new ArrayList<>();
        List<DataValue> discriminators = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecordEntry entry : record.entries()) {
            switch (entry) {
                case FieldDef field -> {
                    FieldDef.FieldType type = field.type().orElseThrow(() -> new SchemaValidationException("field '"
                            + field.name() + "' states only a modifier and no type, and a record written in place "
                            + "declares every field with its type -- a modifier-only entry is a tightening, written "
                            + "in a refinement or composition body (§5.7)"));
                    FieldModifiers.Resolved facts = FieldModifiers.of(field.name(), field.omittable(),
                            type.voidable(), field.modifier());
                    facts.value().filter(token -> token.form() == TokenForm.UNQUOTED
                            && currentParameters.contains(token.text())).ifPresent(token -> {
                                throw ParameterTypes.atValue(token.text(), field.name());
                            });
                    if (FieldModifiers.discriminates(field)) {
                        discriminators.add(WireForm.plain(new TokenValue(field.name(), TokenForm.SINGLE_LINE_QUOTED)));
                    }
                    DataValue value = fieldValue((TypeExpr) type.typeRef(), facts);
                    positions.inPlace().put(value, new SchemaPositions.WrittenField(field.annotations(),
                            positions.of(field)));
                    fields.add(fieldEntry(field.name(), value, seen));
                }
                case GroupDef group -> {
                    for (GroupDef.Member member : group.members()) {
                        DataValue value = fieldValue((TypeExpr) member.typeRef(),
                                new FieldModifiers.Resolved(true, member.voidable(), FieldRole.FREE, Optional.empty()));
                        positions.inPlace().put(value, new SchemaPositions.WrittenField(member.annotations(),
                                Optional.empty()));
                        fields.add(fieldEntry(member.name(), value, seen));
                    }
                    groups.add(groupValue(group.fieldGroup()));
                }
            }
        }
        List<RecordValue.Field> binding = new ArrayList<>();
        binding.add(new RecordValue.Field(WireForm.FIELDS,
                WireForm.plain(fields.isEmpty() ? new EmptyBrace() : new MapValue(fields))));
        if (!groups.isEmpty()) {
            binding.add(new RecordValue.Field(GROUPS, WireForm.plain(new ArrayValue(groups))));
        }
        if (!discriminators.isEmpty()) {
            // `=?` implies ABSTRACT (§5.2), and a record in place nothing can be a member of is empty (§5.10.1).
            binding.add(WireForm.nameField(EXTENSION, "ABSTRACT"));
            binding.add(new RecordValue.Field(DISCRIMINATORS, WireForm.plain(new ArrayValue(discriminators))));
        }
        return new Binding(RECORD, binding);
    }

    /** One entry of a record in place's {@code fields}, its name refused where the record already declares it. */
    private static MapValue.MapEntry fieldEntry(String name, DataValue value, Set<String> seen) {
        if (!seen.add(name)) {
            throw new SchemaValidationException("field '" + name + "' is declared more than once in a record written "
                    + "in place -- a field name is unique across a record's plain fields and all its groups' members "
                    + "(§5.11)");
        }
        return new MapValue.MapEntry(WireForm.plain(new TokenValue(name, TokenForm.SINGLE_LINE_QUOTED)), value);
    }

    /** A field's {@code meta.record_field} value: its type, and each fact its marks state. */
    private static DataValue fieldValue(TypeExpr type, FieldModifiers.Resolved facts) {
        List<RecordValue.Field> members = new ArrayList<>();
        slot(WireForm.TYPE, type, members);
        if (facts.optional()) {
            members.add(WireForm.nameField(OPTIONAL, "true"));
        }
        if (facts.voidable()) {
            members.add(WireForm.nameField(WireForm.VOIDABLE, "true"));
        }
        if (facts.role() != FieldRole.FREE) {
            members.add(WireForm.nameField(ROLE, facts.role().name()));
        }
        facts.value().ifPresent(token -> members.add(new RecordValue.Field(WireForm.VALUE, WireForm.plain(token))));
        return WireForm.plain(new RecordValue(members));
    }

    /** A group's {@code meta.field_group} value, its member names as text. */
    private static DataValue groupValue(FieldGroup group) {
        List<RecordValue.Field> members = new ArrayList<>();
        members.add(new RecordValue.Field(MEMBERS, WireForm.plain(new ArrayValue(group.members().stream()
                .map(option -> WireForm.plain(names(option))).toList()))));
        if (!group.optionalMembers().isEmpty()) {
            members.add(new RecordValue.Field(OPTIONAL_MEMBERS, WireForm.plain(names(group.optionalMembers()))));
        }
        if (group.optional()) {
            members.add(WireForm.nameField(OPTIONAL, "true"));
        }
        return WireForm.plain(new RecordValue(members));
    }

    private static ArrayValue names(List<String> names) {
        return new ArrayValue(names.stream()
                .map(name -> WireForm.plain(new TokenValue(name, TokenForm.SINGLE_LINE_QUOTED))).toList());
    }

    /**
     * §5.3's size specifier as the {@code min_items}/{@code max_items} pair it binds -- one rule for arrays
     * and maps alike, since both constructors declare the same two fields. An exact {@code N} pins both, so
     * {@code [T; 3]} and {@code [T; 3..3]} build one construction.
     *
     * <p><b>A zero floor is rejected</b> rather than desugared, which is §5.3's own rule: "a lower bound of
     * {@code 0} with no upper bound ({@code 0..}) is a resolver error", the form meaning exactly what the
     * unbounded one means.
     */
    private static List<RecordValue.Field> sizeFields(SizeSpec size, String shown) {
        return switch (size) {
            case SizeSpec.Min min when min.lower().equals("0") ->
                    throw new SchemaValidationException("'" + shown + "' pins a floor of zero, which every "
                            + "container already satisfies -- drop the size specifier for the unconstrained "
                            + "form (§5.3)");
            case SizeSpec.Min min -> List.of(WireForm.nameField(MIN_ITEMS, min.lower()));
            case SizeSpec.Max max -> List.of(WireForm.nameField(MAX_ITEMS, max.upper()));
            case SizeSpec.Ranged ranged ->
                    List.of(WireForm.nameField(MIN_ITEMS, ranged.lower()), WireForm.nameField(MAX_ITEMS, ranged.upper()));
            case SizeSpec.Exact exact ->
                    List.of(WireForm.nameField(MIN_ITEMS, exact.bound()), WireForm.nameField(MAX_ITEMS, exact.bound()));
        };
    }

    // ── The construction a binding record denotes ────────────────────────────────────────────────

    /**
     * {@code !head { field: value ... }} -- the construction a binding record denotes. A slot holding an application
     * is written as its reference, {@code box<text>}, for materialisation to close one phase later.
     */
    private static DataValue construction(Binding binding) {
        return new DataValue(List.of(), Optional.of(binding.head()), new RecordValue(binding.fields()));
    }

    /** A declaration whose body is a sugar form is the construction it denotes (§5.3). */
    private static TypeDef instance(Binding binding) {
        return new Instance(construction(binding));
    }

    /**
     * A size bound naming one of the enclosing declaration's parameters, {@code [T; N]}, refused naming the slot: a
     * parameter is a type, and a bound is a count (§5.3, §5.10).
     */
    private void refuseParameterBound(Binding binding) {
        for (RecordValue.Field field : binding.fields()) {
            if ((MIN_ITEMS.equals(field.name()) || MAX_ITEMS.equals(field.name()))
                    && field.value().coreValue() instanceof TokenValue token
                    && currentParameters.contains(token.text())) {
                throw ParameterTypes.at(token.text(), "a size bound (" + binding.head() + "." + field.name() + ")");
            }
        }
    }

    /**
     * The type an element or map-value position holds: an ordinary reference expanded the usual way, or a nested
     * form rewritten into its construction ({@link #typeRef}), which the enclosing binding writes in its wrapper.
     */
    private TypeExpr elementRef(ElementType element) {
        return typeRef(element.typeRef());
    }

    /**
     * How an element position was spelled, for the one diagnostic that quotes the sugar form back at its
     * author: the position's own name when it has one, and a stand-in for a nested form.
     */
    private static String shownElement(ElementType element) {
        return element.typeRef() instanceof NameRef simple ? simple.name() : "T";
    }

    /**
     * Maps {@code items}, returning the original list when every element came back identical. The
     * reference-equality check is what lets an unchanged subtree propagate "nothing changed" all the way up
     * rather than rebuilding every ancestor.
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
