package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ReferenceValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.ApplicationRef;
import io.ltr8.tson.compiler.ast.schema.ArrayExpr;
import io.ltr8.tson.compiler.ast.schema.ChoiceExpr;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.MapExpr;
import io.ltr8.tson.compiler.ast.schema.NameRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;

import io.ltr8.tson.compiler.ast.schema.Reference;
import io.ltr8.tson.compiler.ast.schema.SiblingRef;
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
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.base.SourcePosition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
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
 * DefinitionResolver} with a bare reference or {@code !C value} -- and it already handles the second
 * generically through the governing meta's compiled reader. The arrangement this replaces split the same
 * construct by position: a declaration-position application became a real body in the resolver, while a
 * field-position one was deferred to {@code TsonSchemaLinker}, which sits in a module that cannot reach that
 * generic machinery and so needed a hand-written assembler per constructor shape.
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
 * </pre>
 *
 * This phase therefore consults no governing meta at all. The kernel's container constructors are
 * parameterless (§4.2), so nothing about routing is stated in a meta to be read off it: the table above is
 * the whole rule, and the kernel's own bootstrap needs no special case.
 *
 * <p><b>A nested bracket or brace form expands innermost first.</b> §5.3's declaration-level container
 * syntax nests inside itself -- {@code [[T; N]; N]}, {@code {text =&gt; [order; 1..]}} -- so a position
 * holding one has the inner form injected under its own derived name and becomes a bare reference to it.
 * The enclosing container then routes a plain name like any other, at any depth. See {@link #elementRef}.
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
 * a form over a parameter is admitted only where it is a reference: {@code [T]} and {@code {K => V}} are written as
 * the applications they spell, {@code meta.array<T>} and {@code meta.map<K V>}, held until the record is applied, and
 * every other form over a parameter -- a size, a {@code ?} element, a tuple, a choice -- is refused, naming the form
 * ({@link #openForm}). Nothing is lifted for a form over a parameter; a form naming none lifts closed, as anywhere.
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


    /** The vocabulary fields the desugar table binds -- fixed by the table, not looked up in a governing meta. */
    private static final String ELEMENT_TYPE = "element_type";
    private static final String KEY_TYPE = "key_type";
    private static final String VALUE_TYPE = "value_type";
    private static final String MIN_ITEMS = "min_items";
    private static final String MAX_ITEMS = "max_items";
    private static final String ELEMENTS = "elements";
    private static final String VARIANTS = "variants";


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

    /**
     * Names already in scope from {@code !!import}. A sugar form that generates a name an import already
     * declares is <em>referenced</em> rather than redeclared: the name is derived from the binding record
     * itself, so an identical form in an imported schema has produced the same type. Without this, a schema
     * writing {@code [text; 1..]} would redeclare the one it imports from the kernel and be rejected as a
     * local-vs-import collision. The declaration it would have injected is kept in {@link #reused}, so that
     * {@code SchemaResolver} can hold the import's entry to the form ({@code ImportedSynthetics.requireForm}).
     */
    private final Set<String> imported;

    /** The declaration each reference to an imported synthetic stands for, keyed by its derived name. */
    private final Map<String, SchemaMap.Declaration> reused = new LinkedHashMap<>();

    /** Declarations synthesised for sugar forms encountered during the walk, keyed by their generated name. */
    private final Map<String, SchemaMap.Declaration> injected = new LinkedHashMap<>();

    /** §8.2's freshness MUST over the names this pass mints -- see {@link MintedNames}. */
    private final MintedNames minted = new MintedNames();

    /** This document's own declarations, for {@link #checkTemplateApplication} -- set before the walk starts. */
    private Map<String, SchemaMap.Declaration> local = Map.of();

    /**
     * The type parameters of the declaration currently being walked, or empty outside a template. A sugar form
     * naming one of these is no lift: it is the reference it spells, or refused ({@link #openForm}). Every other form
     * in the same declaration lifts exactly as it would outside a template (§5.3's one lift rule).
     */
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

    private SchemaDesugarer(Set<String> imported, DesugarFailureReporter reporter,
            SchemaPositions positions) {
        this.imported = imported;
        this.reporter = reporter;
        this.positions = positions;
    }

    /**
     * {@link #desugar(SchemaDocument, Set, DesugarFailureReporter, SchemaPositions)}, throwing at the first
     * invalid sugar form and keeping no positions -- a caller with no source text behind the document.
     */
    static SchemaDocument desugar(SchemaDocument document, Set<String> imported) {
        return desugar(document, imported, null, SchemaPositions.none());
    }

    /**
     * The names {@code desugared} holds that {@code original} did not: the entries this phase lifted, and so
     * exactly the schema's <b>synthetic entries</b> ([TSON-SCHEMA] §5.3's lift rule -- closed for a concrete
     * form, open for a parameter-bearing one). Both callers of {@link #desugar} need the set: one to mark
     * each of them {@code @meta.synthetic} at its key (§8.2), and {@code SchemaResolver} also to tell a generated
     * head closing its own intermediate form from an authored one.
     *
     * <p>A set difference rather than a field on the pass, because {@link #hoist} does not inject a form an
     * {@code !!import} already declares -- the imported entry <em>is</em> the same form, resolved by the
     * schema that owns it, and marking it here would put this schema's own derived marker on someone else's
     * key. What the difference reports is what this document gained.
     */
    static Set<String> lifted(SchemaDocument original, SchemaDocument desugared) {
        Set<String> lifted = new LinkedHashSet<>(desugared.body().declarations().keySet());
        lifted.removeAll(original.body().declarations().keySet());
        return lifted;
    }

    /**
     * The document with every expandable sugar form hoisted into its own declaration, or the same instance
     * when there was nothing to expand.
     *
     * <p>A declaration whose sugar form is invalid is reported to {@code reporter} and replaced with {@link
     * #absorbed}, so the declarations around it still expand and go on to resolve -- see {@link
     * #desugarOrReport}. With a {@code null} {@code reporter} the first such form throws instead.
     */
    static SchemaDocument desugar(SchemaDocument document, Set<String> imported,
            DesugarFailureReporter reporter, SchemaPositions positions) {
        return desugar(document, imported, reporter, positions, new LinkedHashMap<>());
    }

    /**
     * {@link #desugar(SchemaDocument, Set, DesugarFailureReporter, SchemaPositions)}, putting into {@code reused}
     * the declaration each lift whose name an import already declares would have injected: the form the import's
     * entry must be.
     */
    static SchemaDocument desugar(SchemaDocument document, Set<String> imported,
            DesugarFailureReporter reporter, SchemaPositions positions, Map<String, SchemaMap.Declaration> reused) {
        SchemaDesugarer pass = new SchemaDesugarer(imported, reporter, positions);
        pass.local = document.body().declarations();
        SchemaMap body = pass.schemaMap(document.body());
        reused.putAll(pass.reused);
        if (body == document.body() && pass.injected.isEmpty()) {
            return document;
        }
        Map<String, SchemaMap.Declaration> declarations = new LinkedHashMap<>(body.declarations());
        for (Map.Entry<String, SchemaMap.Declaration> entry : pass.injected.entrySet()) {
            if (declarations.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalStateException("desugared name '" + entry.getKey()
                        + "' collides with a declaration already in this schema");
            }
        }
        return new SchemaDocument(document.id(), document.imports(),
                new SchemaMap(body.annotations(), declarations));
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
     *
     * <p><b>Anything already injected on behalf of a failed declaration stays injected</b>, and is not rolled
     * back. Injected names are derived from the binding record itself, so a later declaration containing the
     * same form finds and references the existing entry (§8.2's structural-equality rule, which is what makes
     * the injection shared rather than per-use in the first place); removing one because the declaration that
     * happened to reach it first went on to fail would break whichever declaration referenced it second. Left
     * behind, it is an ordinary unreferenced declaration that resolves and links like any other.
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
        siblingsAtFieldTypesOnly(declaration.typeDef());
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

    // ── §5.2's position rule: a sibling reference stands only at a record field's type ─────────────

    /**
     * Refuses a sibling reference ({@code kind!}) written in sugar anywhere but at a record field's type, naming the
     * slot ([TSON-SCHEMA] §5.2). The grammar admits one wherever a type-ref stands, and it is refused here, before
     * any lift: a lift would carry it into an entry outside the record, which has no sibling to read, so {@code
     * [ kind! ]} would need an array chosen per value. A group member is a field of the record, and takes one as a
     * field does. A construction's payload is data, read against its constructor, whose slot readers state the
     * same rule.
     */
    private static void siblingsAtFieldTypesOnly(TypeDef typeDef) {
        switch (typeDef) {
            case ReferenceTypeDef reference -> siblingRefused(reference.ref(), "an alias target");
            case StructuralTypeDef structural -> {
                switch (structural.body()) {
                    case RecordDef record -> siblingsAtFieldTypesOnly(record);
                    case RefinedDef refined -> {
                        siblingRefused(refined.target(), "a refinement source");
                        siblingsAtFieldTypesOnly(refined.body());
                    }
                    case ConstructionDef construction -> {
                        construction.supertypes().forEach(operand -> siblingRefused(operand, "a composition operand"));
                        construction.body().ifPresent(SchemaDesugarer::siblingsAtFieldTypesOnly);
                    }
                }
            }
            default -> { } // a payload is data, judged by its constructor's slot readers
        }
    }

    private static void siblingsAtFieldTypesOnly(RecordDef record) {
        for (RecordEntry entry : record.entries()) {
            switch (entry) {
                case FieldDef field -> field.type().ifPresent(type -> siblingInside(type.typeRef()));
                case GroupDef group -> group.members().forEach(member -> siblingInside(member.typeRef()));
            }
        }
    }

    /** A record field's type: a sibling reference itself stands here, and is refused anywhere inside the type. */
    private static void siblingInside(TypeExpr ref) {
        if (!(ref instanceof SiblingRef)) {
            siblingRefused(ref, "");
        }
    }

    /** {@code ref}, standing at {@code slot}, refused where it is a sibling reference or holds one at any depth. */
    private static void siblingRefused(TypeExpr ref, String slot) {
        switch (ref) {
            case SiblingRef sibling -> throw new SchemaValidationException(
                    io.ltr8.tson.schema.meta.SiblingRef.of(sibling.field()).refusedAt(slot));
            case NameRef _ -> { }
            case ApplicationRef application ->
                    application.args().forEach(argument -> siblingRefused(argument, "a template argument"));
            case ArrayExpr array -> siblingRefused(array.elementType().typeRef(), "an array's element type");
            case MapExpr map -> {
                siblingRefused(map.keyType(), "a map's key type");
                siblingRefused(map.valueType().typeRef(), "a map's value type");
            }
            case TupleExpr tuple -> tuple.elementTypes()
                    .forEach(element -> siblingRefused(element.typeRef(), "a tuple element"));
            case ChoiceExpr choice -> choice.variants().forEach(variant -> siblingRefused(variant, "a choice variant"));
        }
    }

    /**
     * {@code AtomRefinement} and {@code Instance} are passed through: their target is a bare {@code String}
     * type name and their payload is a {@code DataValue}, so neither can carry a sugar form. An {@code Instance}
     * with parameters is the resolver's to admit (before {@code !meta.record}) or refuse, since only it knows what
     * the head is.
     *
     * <p>A template's record body is walked as any record's, its forms over a parameter written as the references
     * they spell ({@link #openForm}); a parameter list before anything but a record body is refused ({@link
     * #refuseTemplateBody}).
     */
    private TypeDef typeDef(TypeDef typeDef) {
        return switch (typeDef) {
            case StructuralTypeDef structural -> {
                // A template's body is walked like any record's: a concrete form inside it lifts to an ordinary
                // closed entry, exactly as it would outside a template, and a form naming one of the declaration's
                // own parameters is the reference it spells, or refused -- see openForm.
                StructuralDef body = structuralDef(structural.body());
                yield body == structural.body() ? structural
                        : new StructuralTypeDef(structural.typeParams(), body);
            }
            // A declaration's own body reference names what this declaration *is*; only its arguments are
            // expandable, so the head stays put and its own handling is unchanged.
            case ReferenceTypeDef reference when !reference.typeParams().isEmpty() ->
                    throw refuseTemplateBody(reference.ref());
            case ReferenceTypeDef reference -> {
                // **A declaration's own body never lifts** (§5.3): the form *is* the construction, so it
                // becomes the instance directly rather than a reference to an injected one. That is what
                // keeps `score_list => [integer; 1..]` a PRODUCT entry with a real body, and
                // `contact => (email | phone)` a SUM entry with a real ChoiceBody, instead of aliases to
                // ones. Every sugar form takes this path -- the bracket forms reach it through
                // `type-ref` like the rest, since there is no separate declaration-level tier.
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

    /**
     * A sugar form standing inside an open record, over one of its parameters ([TSON-SCHEMA] §5.10): admitted only
     * where it is a reference. {@code [T]} and {@code {K => V}} are the applications they spell, {@code meta.array<T>}
     * and {@code meta.map<K V>}, held as written until the record is applied; a size or a {@code ?} sets a field no
     * argument list carries, and a tuple or a choice is no constructor over reference fields, so each is refused,
     * naming the form. {@code children} are the form's positions, already expanded.
     */
    private TypeExpr openForm(TypeExpr written, List<TypeExpr> children) {
        String parameter = children.stream().map(this::parameterIn).flatMap(Optional::stream).findFirst()
                .orElseThrow();
        String prefix = "'" + currentName + "': '" + spell(written) + "' stands over the parameter '" + parameter
                + "' and is no reference, and inside an open record a form over a parameter is admitted only "
                + "where it is one (§5.10) -- ";
        return switch (written) {
            case ArrayExpr array when array.size().isEmpty() && !array.elementType().voidable() ->
                    new ApplicationRef(ARRAY, children);
            case MapExpr map when map.size().isEmpty() && !map.valueType().voidable() ->
                    new ApplicationRef(MAP, children);
            case ArrayExpr _, MapExpr _ -> throw new SchemaValidationException(prefix + "a size or a '?' sets a "
                    + "field of the container that no argument list carries, and '[T]' and '{K => V}' alone are "
                    + "the references 'meta.array<T>' and 'meta.map<K V>'. Declare a refined constructor and apply "
                    + "it ('nonempty_type => meta.array ^ { min_items?: = 1 }', then 'nonempty_type<" + parameter
                    + ">'), or move the container into the argument");
            case ChoiceExpr _ -> throw new SchemaValidationException(prefix + "a choice is no constructor over "
                    + "reference fields, and an untagged sum over a parameter cannot be judged disjoint until it is "
                    + "applied (§5.4). Write the alternative as a field group, '( body: " + parameter
                    + " | problem: problem )'");
            default -> throw new SchemaValidationException(prefix + "a tuple's positions are a list of records, not "
                    + "reference fields. Name the positions as fields of the record");
        };
    }

    /** The first of the enclosing declaration's parameters {@code ref} names, as itself or in an argument. */
    private Optional<String> parameterIn(TypeExpr ref) {
        return switch (ref) {
            case NameRef simple -> currentParameters.contains(simple.name()) ? Optional.of(simple.name())
                    : Optional.empty();
            case ApplicationRef generic -> generic.args().stream().map(this::parameterIn).flatMap(Optional::stream)
                    .findFirst();
            default -> Optional.empty();
        };
    }

    /** A form as the author spells it, for a message. */
    private static String spell(TypeExpr ref) {
        return switch (ref) {
            case NameRef simple -> simple.name();
            case SiblingRef sibling -> sibling.field() + "!";
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
                Reference target = refined.target() instanceof ApplicationRef generic
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
                TypeExpr ref = typeRef(fieldType.typeRef());
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
        TypeExpr ref = typeRef(member.typeRef());
        return ref == member.typeRef() ? member
                : new GroupDef.Member(member.annotations(), member.name(), member.omittable(), ref,
                        member.voidable());
    }

    /**
     * A reference at a position where a sugar form <em>is</em> expandable: expands children first, so a
     * nested form is already a plain name by the time the enclosing one is built (<code>{text =&gt;
     * [integer]}</code> injects the inner array, then the outer map referring to it).
     */
    private TypeExpr typeRef(TypeExpr ref) {
        return switch (ref) {
            case NameRef simple -> simple;
            case SiblingRef sibling -> sibling; // a field's type, as written: it names no entry to lift
            case ArrayExpr array -> container(ref, List.of(elementRef(array.elementType())));
            case MapExpr map -> container(ref, List.of(typeRef(map.keyType()), elementRef(map.valueType())));
            case TupleExpr tuple -> container(ref, tuple.elementTypes().stream().map(this::elementRef).toList());
            case ChoiceExpr choice -> container(ref, mapShared(choice.variants(), this::typeRef));
            case ApplicationRef generic -> applicationArguments(generic);
        };
    }

    /**
     * A sugar form whose positions are {@code children}, already expanded: the reference it spells or refused where
     * one of them names a parameter of the enclosing declaration ({@link #openForm}), and lifted closed otherwise.
     */
    private TypeExpr container(TypeExpr ref, List<TypeExpr> children) {
        if (children.stream().anyMatch(child -> parameterIn(child).isPresent())) {
            return openForm(ref, children);
        }
        return hoistOrKeep(binding(ref, children), ref);
    }

    /** Expands only within a reference's arguments, leaving its own head in place. */
    private TypeExpr argumentsOnly(TypeExpr ref) {
        return ref instanceof ApplicationRef generic ? applicationArguments(generic) : ref;
    }

    /** {@code generic} with its arguments expanded, the same node where none changes. */
    private ApplicationRef applicationArguments(ApplicationRef generic) {
        checkTemplateApplication(generic.name());
        List<TypeExpr> args = mapShared(generic.args(), this::typeRef);
        return args == generic.args() ? generic : new ApplicationRef(generic.name(), args);
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
     * binds, in the order the table above lists them. Everything downstream -- the emitted {@code !C { ... }},
     * the derived entry name, the bound-coherence check -- reads this and nothing else.
     */
    private record Binding(String head, List<RecordValue.Field> fields) {
    }

    /** One scalar type slot as the wire field a construction writes -- see {@link #refValue}. */
    private static void refSlot(String slot, TypeExpr ref, List<RecordValue.Field> fields) {
        fields.add(new RecordValue.Field(slot, WireForm.plain(refValue(ref))));
    }

    /**
     * What a type slot holds: a bare token for a plain name, and an application as the data grammar's reference,
     * unquoted ({@link WireForm#refValue}). <b>One spelling per shape, produced in one place</b> -- a slot written two ways
     * is a slot two phases disagree about, and {@link DerivedName#ofBinding} hashes what is written, so a second
     * spelling of one reference would split one type across two entries.
     */
    private static CoreValue refValue(TypeExpr ref) {
        return WireForm.refValue(reference(ref));
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

    /** One position of a tuple after expansion: the type it names, and whether it is marked voidable. */
    private record Position(TypeExpr typeRef, boolean voidable) {
    }

    /**
     * A declaration-level container as the binding record it denotes, or empty when a position holds a form
     * this phase cannot reduce to a name -- which leaves the whole container unexpanded, since a partially
     * reduced one would be a differently-broken shape rather than a recognisable sugar form.
     */
    private Optional<Binding> binding(TypeExpr ref) {
        return switch (ref) {
            case ArrayExpr array -> binding(ref, List.of(elementRef(array.elementType())));
            case MapExpr map -> binding(ref, List.of(typeRef(map.keyType()), elementRef(map.valueType())));
            case TupleExpr tuple -> binding(ref, tuple.elementTypes().stream().map(this::elementRef).toList());
            case ChoiceExpr choice -> binding(ref, mapShared(choice.variants(), this::typeRef));
            default -> Optional.empty();
        };
    }

    /** {@link #binding(TypeExpr)} over the form's positions, {@code children}, already expanded. */
    private static Optional<Binding> binding(TypeExpr ref, List<TypeExpr> children) {
        return switch (ref) {
            case ArrayExpr array -> arrayBinding(children.getFirst(), array.elementType().voidable(), array.size(),
                    shownElement(array.elementType()));
            case MapExpr map -> mapBinding(children.get(0), children.get(1), map.valueType().voidable(), map.size());
            case TupleExpr tuple -> {
                List<Position> positions = new ArrayList<>();
                for (int i = 0; i < children.size(); i++) {
                    positions.add(new Position(children.get(i), tuple.elementTypes().get(i).voidable()));
                }
                yield tupleBinding(positions);
            }
            case ChoiceExpr _ -> choiceBinding(children);
            default -> Optional.empty();
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
    private static Optional<Binding> arrayBinding(TypeExpr element, boolean voidable, Optional<SizeSpec> size,
            String shown) {
        if (!isReference(element)) {
            return Optional.empty();
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        refSlot(ELEMENT_TYPE, element, fields);
        if (voidable) {
            fields.add(WireForm.nameField(WireForm.VOIDABLE, "true"));
        }
        size.ifPresent(spec -> fields.addAll(sizeFields(spec, "[" + shown + "; 0..]")));
        return Optional.of(new Binding(ARRAY, fields));
    }

    /**
     * Whether a position holds something this table can put in a type slot: a name, or a name carrying
     * arguments. Anything else is a sugar form the caller was supposed to have expanded first, and leaves the
     * enclosing container unexpanded rather than half-built.
     */
    private static boolean isReference(TypeExpr ref) {
        return ref instanceof TypeRef;
    }

    /**
     * <code>!meta.map { key_type: K  value_type: V [voidable: true] [min_items: N] [max_items: M] }</code> --
     * the map row of the desugar table. The <b>value</b> carries {@code voidable} the way an array element
     * does, written only when marked, for the reason {@link #arrayBinding} omits an unmarked element's: the
     * kernel's default is false, so writing it would put a field at its default value into every map.
     * The key carries none -- a key is never void ([TSON-DATA] §2.9), so the parser refuses the marker
     * there rather than this table dropping it.
     */
    private static Optional<Binding> mapBinding(TypeExpr key, TypeExpr value, boolean voidable,
            Optional<SizeSpec> size) {
        if (!isReference(key) || !isReference(value)) {
            return Optional.empty();
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        refSlot(KEY_TYPE, key, fields);
        refSlot(VALUE_TYPE, value, fields);
        if (voidable) {
            fields.add(WireForm.nameField(WireForm.VOIDABLE, "true"));
        }
        size.ifPresent(spec -> fields.addAll(
                sizeFields(spec, "{" + shownRef(key) + " => " + shownRef(value) + "; 0..}")));
        return Optional.of(new Binding(MAP, fields));
    }

    /** How a map side is quoted back in the one diagnostic that shows the form. */
    private static String shownRef(TypeExpr ref) {
        return ref instanceof NameRef simple ? simple.name() : ((ApplicationRef) ref).name() + "<...>";
    }

    /**
     * {@code !meta.tuple { elements: [{ element_type: T } { element_type: U }] }} -- the tuple row of the table.
     *
     * <p><b>Why this is not {@link #choiceBinding}.</b> Both are variadic and both fill one collection-typed
     * vocabulary field. What differs is what one position <em>is</em>: a variant is a bare reference,
     * while an element is a {@code meta.tuple_element} record carrying a type <em>and</em> its own {@code voidable},
     * so each position needs a record built for it rather than a name token. {@code voidable} is written only
     * for a marked position, for the reason {@link #arrayBinding} omits an unmarked element's.
     *
     * <p>What the two do share is that a position's type is written through {@link #refValue}: an element
     * holding a §5.10 application is spelled as its reference and rewritten one pass later,
     * exactly as a variant is.
     */
    private static Optional<Binding> tupleBinding(List<Position> positions) {
        List<DataValue> elements = new ArrayList<>();
        for (Position position : positions) {
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(new RecordValue.Field(ELEMENT_TYPE, WireForm.plain(refValue(position.typeRef()))));
            if (position.voidable()) {
                members.add(WireForm.nameField(WireForm.VOIDABLE, "true"));
            }
            elements.add(WireForm.plain(new RecordValue(members)));
        }
        return Optional.of(new Binding(TUPLE,
                List.of(new RecordValue.Field(ELEMENTS, WireForm.plain(new ArrayValue(elements))))));
    }

    /**
     * {@code !meta.choice { variants: [A B ...] }} -- the choice row of the table. Variants arrive already
     * expanded: a nested inline form is hoisted by the caller first, so what reaches here is a {@link
     * NameRef} or -- for a §5.10 application, which has no entry to be hoisted to until materialisation
     * mints one -- an {@code ApplicationRef}.
     *
     * <p><b>A variant is written through {@link #refValue}, like every other type slot.</b>
     * A collection-valued slot is no different from a named one in what it holds: {@code variants} is a
     * {@code [meta.type_ref]}, so an application standing in it is spelled as its reference and
     * rewritten to the entry it denotes one pass later ({@code MetaRefs.mapRefsKeepingChain}, which maps
     * a choice's variants like any other reference). Refusing the application here instead left the whole
     * choice unlifted, and a form the desugar table did not reduce reaches {@code DefinitionResolver} as a
     * {@code ChoiceExpr} it has no case for.
     *
     * <p>Distinctness of the variants (§5.4: "the resolver validates that each variant resolves to a distinct
     * type") is deliberately not checked here -- it is a question about what the names <em>resolve</em> to,
     * after alias flattening, which this phase has no answer to.
     */
    private static Optional<Binding> choiceBinding(List<TypeExpr> variants) {
        List<DataValue> members = new ArrayList<>();
        for (TypeExpr variant : variants) {
            members.add(WireForm.plain(refValue(variant)));
        }
        return Optional.of(new Binding(CHOICE,
                List.of(new RecordValue.Field(VARIANTS, WireForm.plain(new ArrayValue(members))))));
    }

    /**
     * §5.3's size specifier as the {@code min_items}/{@code max_items} pair it binds -- one rule for arrays
     * and maps alike, since both constructors declare the same two fields. An exact {@code N} pins both, so
     * {@code [T; 3]} and {@code [T; 3..3]} land on the very same entry.
     *
     * <p><b>A zero floor is rejected</b> rather than desugared, which is §5.3's own rule: "a lower bound of
     * {@code 0} with no upper bound ({@code 0..}) is a resolver error". More than a style nit --
     * identity is structural (§8.2), so the form
     * lands on an entry <em>distinct from</em> the unbounded one that means exactly the same thing. That is an
     * identity trap, and the author's fix is the one §5.3 itself names.
     */
    private static List<RecordValue.Field> sizeFields(SizeSpec size, String shown) {
        return switch (size) {
            case SizeSpec.Min min when min.lower().equals("0") ->
                    throw new SchemaValidationException("'" + shown + "' pins a floor of zero, which every "
                            + "container already satisfies -- drop the size specifier for the unconstrained "
                            + "form (§5.3). The spelling is not merely redundant: identity is structural "
                            + "(§8.2), so it lands on an entry distinct from the unconstrained one that means "
                            + "the same thing");
            case SizeSpec.Min min -> List.of(WireForm.nameField(MIN_ITEMS, min.lower()));
            case SizeSpec.Max max -> List.of(WireForm.nameField(MAX_ITEMS, max.upper()));
            case SizeSpec.Ranged ranged ->
                    List.of(WireForm.nameField(MIN_ITEMS, ranged.lower()), WireForm.nameField(MAX_ITEMS, ranged.upper()));
            case SizeSpec.Exact exact ->
                    List.of(WireForm.nameField(MIN_ITEMS, exact.bound()), WireForm.nameField(MAX_ITEMS, exact.bound()));
        };
    }

    // ── Hoisting: a sugar form becomes a declaration plus a reference to it ──────────────────────

    /**
     * {@code !head { field: value ... }} -- the construction a binding record denotes.
     *
     * <p><b>A slot holding an application is written as its reference</b>, {@code box<text>}, since an
     * application has no bare-token spelling: the entry it denotes does not exist until materialisation, one
     * phase later. The construction therefore names something that is not yet an entry, and that is fine --
     * materialisation rewrites every closed entry's references afterwards, so {@code [box<text>]} resolves to
     * an array whose {@code element_type} is {@code box<text>} and then, one pass on, to one whose element is
     * the instantiation entry. The entry dangles for exactly the window an ordinary forward reference does.
     */
    private static TypeDef instance(Binding binding) {
        return new Instance(new DataValue(List.of(), Optional.of(binding.head()), new RecordValue(binding.fields())));
    }

    /**
     * A binding hoisted into its own declaration and replaced by a reference to it, or {@code unexpanded} when
     * the form did not reduce.
     */
    private TypeExpr hoistOrKeep(Optional<Binding> binding, TypeExpr unexpanded) {
        return binding.<TypeExpr>map(this::hoist).orElse(unexpanded);
    }

    /**
     * Records an injected declaration under its derived name and yields the reference that replaces the sugar.
     *
     * <p><b>Which entry it lifts to is §5.3's one lift rule</b>: a closed construction referenced by a bare name,
     * whether or not the declaration around it is a template. A form naming a parameter never reaches here: it is
     * the reference it spells, or refused ({@link #openForm}).
     */
    private TypeExpr hoist(Binding binding) {
        refuseParameterBound(binding);
        String name = bindingName(binding);
        claim(name, binding, () -> new SchemaMap.Declaration(List.of(), name, List.of(),
                Optional.empty(), instance(binding)));
        return new NameRef(name);
    }

    /**
     * A size bound naming one of the enclosing declaration's parameters, {@code [T; N]}, refused naming the slot: a
     * parameter is a type, and a bound is a count (§5.3, §5.10). Refused here, before the form lifts closed with a
     * parameter in it, so the refusal names the declaration that wrote it and not a synthetic.
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
     * The type-ref an element or map-value position denotes: an ordinary reference expanded the usual way, or
     * a <b>nested declaration-level form</b> hoisted into its own declaration and replaced by its name.
     *
     * <p>§5.3's declaration-level container syntax nests inside itself ({@code [[T; 2], U]},
     * <code>{text =&gt; [order; 1..]}</code>), and the inner form desugars first. That is the bottom-up hoist
     * {@link #typeRef} already performs for an inline form, one tier down: the inner container's own binding
     * record is built and injected, and the position that held it becomes a bare reference, so the enclosing
     * container routes a plain name like any other.
     *
     * <p>Empty when the position holds a form this phase cannot build, which leaves the enclosing container
     * unexpanded and keeps its existing handling.
     */
    private TypeExpr elementRef(ElementType element) {
        return typeRef(element.typeRef());
    }

    /**
     * How an element position was spelled, for the one diagnostic that quotes the sugar form back at its
     * author: the position's own name when it has one, and a stand-in when it is an inline or nested form
     * whose expansion carries a derived name the author never wrote.
     */
    private static String shownElement(ElementType element) {
        return element.typeRef() instanceof NameRef simple ? simple.name() : "T";
    }

    // ── Internal names (§8.2) ────────────────────────────────────────────────────────────────────

    /**
     * Mints {@code name} for {@code binding} (§8.2's freshness, {@link MintedNames}) and injects its declaration the
     * first time, or records it in {@link #reused} where an import already declares the name: the import's entry
     * stands for it, and is held to it once resolution can read the form.
     */
    private void claim(String name, Binding binding, Supplier<SchemaMap.Declaration> declaration) {
        if (minted.claim(name, DerivedName.canonicalBinding(binding.head(), binding.fields()))) {
            (imported.contains(name) ? reused : injected).put(name, declaration.get());
        }
    }

    private static String bindingName(Binding binding) {
        return DerivedName.ofBinding(binding.head(), binding.fields());
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
