package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.compiler.TsonMissingBindingException;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonWriteException;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.ChoiceRef;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.RemovalSet;
import io.ltr8.tson.compiler.ast.schema.ArrayRef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeRef;
import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.atom.IdentifierParser;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves declarations from a {@link SchemaMap} (the grammar-layer AST, {@code tson-compiler}) into
 * {@link TypeDefinition}s (Part 2 §4, §8) -- an incremental, deliberately narrow resolver, not the
 * full two-pass resolver of §3.4.1. It handles eight constructs so far:
 *
 * <ul>
 *   <li>A record (no supertypes), optionally {@code ~}-marked (the {@code constructor} flag
 *   threads straight from {@code StructuralTypeDef.constructor()} into the result) and optionally
 *   parameterized ({@code <T, ...>}, threaded straight into {@code TypeDefinition.parameters} with
 *   no substitution or usage validation, see below), whose fields are simple type-refs or the
 *   inline array sugar {@code [T]} (see below), each REQUIRED or OPTIONAL (a {@code ?} suffix), and
 *   whose entries may include field groups (§5.11) -- {@code integer_size}'s own shape, and (via a
 *   {@code ~atom & {...}} composition body, see below) {@code integer_type}'s.</li>
 *   <li>Composition ({@code A & B & { ... }}, §5.8), also optionally {@code ~}-marked and
 *   optionally parameterized, over supertypes that are themselves already resolved, simple
 *   (non-generic) references, whose own body is a {@link RecordBody} -- {@code atom => top & {}},
 *   {@code product => top & { access_pattern: ... size_type: ... }}, {@code sum => top & {}},
 *   {@code reference => top & { target: type_name } }, and {@code integer_type => ~atom & { size:
 *   integer_size? ( min: integer | exclusive_min: integer )? ... }}'s own shapes -- a trailing-body
 *   field naming an inherited field is now a *tightening* entry (§5.7, see below and {@link
 *   #resolveTighteningField}) rather than an automatic error, which is what lets {@code array}'s and
 *   {@code map}'s own {@code <T> ~product & {...}}/{@code <K, V> ~product & {...}} shapes -- each
 *   re-declaring {@code product}'s {@code access_pattern}/{@code size_type} with a fixed value --
 *   resolve end-to-end.</li>
 *   <li>A bare, argument-free type reference ({@code name => other_name}, §8.3) -- always resolves
 *   to a {@code REFERENCE}-kind entry regardless of what the referenced name itself resolves to
 *   (e.g. {@code type_name => token} is {@code kind: REFERENCE} even though {@code token} itself
 *   is {@code kind: ATOM}) -- {@code type_name}/{@code field_name}/{@code param_name}/{@code
 *   annotation}/{@code documentation}/{@code doc}/{@code alias}'s own shape. No namespace lookup
 *   happens here either: the referenced name is carried through as a bare string, unverified,
 *   exactly like an ordinary field's type-ref.</li>
 *   <li><b>The sugar forms no longer arrive here.</b> {@code SchemaDesugarer} rewrites them before
 *   resolution: {@code [T]} and any constructor application become an {@code !C value} instance
 *   (§5.6), and a sized form becomes one too, its bounds bound straight onto the injected {@code array}
 *   entry with no size template in between (§5.3). What still reaches this class carrying arguments is a
 *   <em>template</em> application, resolved to a {@code REFERENCE} naming it -- see {@link
 *   #resolveTemplateApplication}. The one exception is {@code MetaKernelBootstrapResolver}, which
 *   bypasses {@code SchemaResolver} and so never desugars.</li>
 *   <li>A field's default ({@code ~}) or fixed ({@code =}) modifier value (§5.2, §5.10) on a
 *   REQUIRED (non-{@code ?}) field -- see {@link #resolveField} for the full literal-vs-parameter
 *   split ({@code product_access_type = INDEX} vs. {@code type_ref = T}). Verified against the real
 *   fixture's {@code tuple_element}/{@code field_group} (both fresh records, so untangled from
 *   tightening) and, since no real fixture entry exercises the fixed/parametric cases in isolation
 *   from a tightening composition, small hand-built snippets mirroring {@code array}'s own field
 *   shapes ({@code product_access_type = INDEX}, {@code type_ref = T}, {@code integer ~ N}).</li>
 *   <li>Tightening a composition-body field against an already-inherited one (§5.7, via {@code
 *   inheritedFieldIndex} in {@link #resolveEntry} and {@link #resolveTighteningField}) -- the
 *   tightened field replaces the inherited one in place (§5.8's field-ordering rule), its target
 *   state is checked against §5.7's transition table ({@link #isValidTighteningTransition}), and an
 *   elided type-ref (a modifier-only entry, {@code field: = value}) inherits the source field's type
 *   (§5.7's "Elided type-refs"). Verified end-to-end against the real fixture's {@code array} and
 *   {@code map} -- both tighten {@code product}'s {@code access_pattern}/{@code size_type} to
 *   {@code REQUIRED_FIXED} -- plus hand-built snippets for a rejected invalid transition and an
 *   elided-type-ref tightening (mirroring §5.7's own {@code production => config ^ { host: =
 *   "prod.example.com" } } worked example, adapted to a composition body).</li>
 *   <li>The {@code ^} refinement operator (§5.7, {@code RefinedDef}, via {@link
 *   #resolveRefinement}) -- {@code source ^ { ... }}, optionally {@code ~}-marked and/or
 *   parameterized: {@code set}'s own {@code <T> ~array<T> ^ { state: = REQUIRED ... }}. Unlike
 *   composition, a refinement copies the source's *entire* field set and admits no new fields --
 *   every body entry MUST tighten an inherited field (reusing {@link #resolveTighteningField}) or
 *   the declaration is a resolver error, reported as such ({@link
 *   io.ltr8.tson.schema.TsonSchemaValidationException}) rather than as a coverage gap. {@code source} is
 *   recorded verbatim as the result's own
 *   {@code source} (unlike composition, which never sets it); {@code supertypes} accumulates by the
 *   same induction as composition ({@code [sourceName] + source.supertypes()}); the body's own
 *   {@code record.supertypes} stays empty (that field records only direct {@code &} compositions,
 *   §8.1, and a refinement has none). Verified end-to-end against the real fixture's {@code set},
 *   which refines {@code array}, tightening {@code REQUIRED_DEFAULT} fields to {@code REQUIRED_FIXED}
 *   (§5.7's table). A body entry may also <b>restate a group</b> (§5.11, via {@link
 *   #restatesInheritedGroup}, shared with the composition path): same member labels in the same order,
 *   types verbatim, state tightening OPTIONAL&#8594;REQUIRED only.</li>
 * </ul>
 *
 * Everything else -- the identity-diagonal value-invariant
 * for a restated FIXED field, a generic type-ref with a nested or value (non-simple) argument, and a
 * parameterized supertype ({@code customer & box<T>}, §5.8, which needs §5.10 substitution into the
 * absorbed fields) -- is explicitly out of scope for now and reported via {@link
 * UnsupportedOperationException} rather than silently mis-resolved; each is a later, separate pass.
 * (Constructor application / atom
 * instances -- {@code !C value}, {@link Instance} -- and atom refinement -- {@code !I ^ { ... }},
 * {@link AtomRefinement} -- are both dispatched, via {@link #resolveInstance}/{@link
 * #resolveAtomRefinement} below.)
 *
 * <p>{@link UnsupportedOperationException} means "this construct isn't implemented yet"; a genuine
 * schema error a coverage gap can't explain is a {@link
 * io.ltr8.tson.schema.TsonSchemaValidationException} instead. The distinction is not cosmetic: only the
 * validation exception is collected into a {@code Diagnostic} by {@code SchemaResolver}'s reporting
 * overload, so misfiling an author error as a gap both aborts the run and tells the author their correct
 * understanding of the spec is this library's fault. A useful test for which is which: <b>a schema error's
 * verdict does not change when this library improves; a gap's does.</b> Cases: an atom refinement that
 * loosens its source rather than tightening it ({@link #checkNarrows}); a name in a {@code !} position that
 * resolves in neither namespace ({@link #resolveConstructorTarget}, {@link #resolveAtomRefinement}); a
 * {@code !} form used against the wrong kind of target -- refining a constructor, applying a
 * non-constructor, or refining a non-atom -- each of which the message answers with the form the author
 * probably meant; and a body (or an annotation value) the governing meta's compiled reader rejects, which
 * arrives as a {@link TsonReadException} and is restated as a schema error rather than passed on in the
 * reader's own currency ({@link #bodyIsNotValidData}).
 *
 * <p>Declarations are resolved against two separate namespaces (§3.3.1), each exposed through a
 * required constructor parameter rather than threaded through individual method calls, since both
 * are fixed for as long as this resolver is used:
 * <ul>
 *   <li>{@code namespaceDefinitions} -- the type-name namespace: entries already resolved earlier in
 *   the same schema map, consulted by composition's supertype lookup (§5.8), refinement's source
 *   lookup (§5.7), and atom refinement's source lookup (§5.5). Never populated by this class itself
 *   -- a caller supplies a {@link DefinitionGetter} closing over its own growing map (typically
 *   {@code entries::get}), putting each result into that map itself as it resolves one declaration
 *   at a time. A supertype/source must therefore already be declared earlier in the same schema map
 *   than anything referencing it; real forward references and cross-schema imports need the full
 *   namespace population of §3.3.2/§3.4.1's Pass 1, not implemented here.</li>
 *   <li>{@code metaDefinitions} -- the structure namespace: the governing meta-schema's own entries,
 *   one hop via {@code !!meta}, consulted only by {@link #resolveConstructorTarget} for a
 *   constructor-application target ({@code !C value}). Atom refinement ({@code !I ^ { ... }}) never
 *   consults it.</li>
 * </ul>
 * Either getter can be an always-{@code null} lookup for a resolver that never needs it (e.g. {@link
 * MetaKernelBootstrapResolver}'s own first pass, which never reaches {@link #resolveInstance}).
 *
 * <p><b>Kind determination</b> (§4.1) checks the transitive supertype chain for the literal,
 * kernel-fixed names {@code atom}/{@code product}/{@code sum} -- not a general "inherit the nearest
 * ancestor's own kind" rule (that would be wrong: {@code atom} the type-definition entry is itself
 * {@code kind: PRODUCT}, since {@code atom}'s own supertype chain is just {@code [top]}, which
 * contains none of the three). Zero found -&gt; {@code PRODUCT} (structural default); exactly one
 * -&gt; that kind; two or more -&gt; a resolver error (reported here as {@link
 * UnsupportedOperationException}, not yet a proper diagnostic). A fresh (non-composed) record has
 * an empty chain by construction, so it is always {@code PRODUCT} regardless of {@code ~}.
 *
 * <p><b>Field groups (§5.11) flatten</b>: each member becomes an ordinary {@link RecordField} in
 * source position with state {@link FieldState#OPTIONAL} regardless of the group's own state (the
 * spec's own rule -- a REQUIRED group still means each *member* is individually optional, since at
 * most one is guaranteed, not which), and the group itself is recorded as a {@link FieldGroup}
 * (state {@link ElementState#REQUIRED}/{@link ElementState#OPTIONAL} from the group's own {@code ?}).
 * A composed supertype's groups are inherited whole, in supertype order, ahead of the body's own.
 *
 * <p><b>{@code subtypes} is never populated</b> -- computing it requires a reverse index over the
 * *whole* resolved schema (who lists me as a supertype, transitively), a global pass over every
 * entry, not a per-declaration concern; deliberately deferred, not forgotten.
 *
 * <p><b>{@code parameters} (§5.10) threads straight through</b> from a fresh record's or a
 * composition's own {@code StructuralTypeDef.typeParams()} -- {@code array => <T> ~product & {
 * ... }}'s own {@code [T]} -- with no substitution into field types and no validation that a
 * parameter is actually used anywhere in the body; a reference-declaration's own type parameters
 * ({@code text_keyed_map => <V> map<text, V>}, an open template application) are a separate,
 * not-yet-resolved case.
 *
 * <p>Note the two {@code TypeRef}s in play: this class imports {@code tson-compiler}'s grammar-layer
 * {@link TypeRef} (a source-text reference) for reading the AST, and refers to {@code
 * io.ltr8.tson.schema.meta.TypeRef} (the resolved reference it produces) by its fully-qualified
 * name -- the two share a name (matching the kernel's own single {@code type_ref} vocabulary type)
 * but live in different packages and are different concepts, so only one can be the unqualified
 * import here.
 *
 * <p>Package-private, no {@code Tson} prefix -- internal machinery a consumer of this library never
 * names directly (see "Naming convention" in this project's own CLAUDE.md). {@link SchemaResolver}
 * is the public, document-level counterpart: it validates a document's own header directives ({@code
 * !!id}/{@code !!import}), merges {@code !!import} entries, derives the structure namespace from a
 * {@code TsonCompiledSchemaLoader}, and holds one instance of this class to do the actual
 * per-declaration work. This class never references {@code TsonCompiledSchemaLoader} or {@code
 * SchemaDocument} at all --
 * everything here takes a bare declaration or an already-parsed {@code SchemaMap} entry.
 *
 * <p>Has no dependency on {@code reader} -- {@link #bindAtomInstance}'s own binding
 * step goes through {@link DefinitionMetaReader} (a required constructor parameter), a narrow read
 * contract rather than the full {@code TsonCompiledSchema}; see {@code SchemaResolver#resolveSchema}'s
 * own Javadoc for where that fuller reach actually lives.
 */
final class DefinitionResolver {

    /**
     * The kernel's alias constructor -- §5.10's partial application, {@code <B> pair<uuid, B>}, whose held
     * body is a {@code !reference { target: ... }} and whose entry is {@link TypeKind#REFERENCE} rather than
     * the constructor's own kind.
     */
    private static final String REFERENCE = "reference";

    /** [TSON-SCHEMA] §4.1's structural root -- the name {@link #requireApplicable} tests IS-A against. */
    private static final String TOP = "top";

    /**
     * Re-serializes an atom refinement's source back to wire form for {@link #mergeWithSource} -- see
     * {@link #resolveAtomRefinement}. Structural, not incidental: the merge has to happen on the wire
     * record, so this is the only way to get the source's already-bound facets back into one.
     */
    private final TsonObjectWriter writer = new TsonObjectWriter();

    private final DefinitionMetaReader definitionMetaReader;
    private final AnnotationValueReader annotationValueReader;
    private final DefinitionGetter metaDefinitions;
    private final DefinitionGetter namespaceDefinitions;

    /**
     * Whether this resolver can bind annotation values at all -- false only for a caller with no compiled
     * governing meta to read one through (the meta-kernel bootstrap, which is producing the very entries such
     * a reader would need). It is what separates "this annotation's type is out of reach here" from "this
     * annotation's type does not resolve", the second of which is the author's error; see
     * {@link #bindAnnotationValue}.
     */
    private final boolean annotationsResolve;

    /**
     * Closes a fully-bound template application into the entry it denotes, returning that entry's name.
     * Needed at the two positions that absorb a supertype's <em>fields</em> -- a composition supertype and a
     * refinement source -- because those resolve here, while the rest of §5.10 materialisation runs as a
     * pass afterwards. Everywhere else an application is simply carried as a {@code type_ref} and closed
     * later.
     *
     * <p>Defaults to refusing, for the callers that resolve a declaration outside a whole-schema pass (the
     * meta-kernel bootstrap, and unit tests) -- there is no materialiser in those, and no schema for an
     * instantiation entry to land in.
     */
    private final ApplicationCloser applicationCloser;

    /**
     * Where each field of this document was written, for the position a resolved {@link RecordField} carries.
     * Empty for a resolver with no source text behind it (the bootstrap, a hand-built document), which is
     * why a field's position is {@code Optional} rather than assumed.
     */
    private final SchemaPositions positions;

    /**
     * No annotation reader: an annotation's name is kept, its value is out of reach, and no name is checked
     * against the structure namespace. See {@link AnnotationValueReader}.
     */
    DefinitionResolver(DefinitionMetaReader definitionMetaReader, DefinitionGetter metaDefinitions,
                        DefinitionGetter namespaceDefinitions) {
        this(definitionMetaReader, null, metaDefinitions, namespaceDefinitions, null,
                SchemaPositions.none());
    }

    DefinitionResolver(DefinitionMetaReader definitionMetaReader, AnnotationValueReader annotationValueReader,
                        DefinitionGetter metaDefinitions, DefinitionGetter namespaceDefinitions,
                        ApplicationCloser applicationCloser, SchemaPositions positions) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.definitionMetaReader = Objects.requireNonNull(definitionMetaReader, "definitionMetaReader");
        this.annotationsResolve = annotationValueReader != null;
        this.annotationValueReader = annotationValueReader == null ? (type, value) -> null : annotationValueReader;
        this.metaDefinitions = Objects.requireNonNull(metaDefinitions, "metaDefinitions");
        this.namespaceDefinitions = Objects.requireNonNull(namespaceDefinitions, "namespaceDefinitions");
        this.applicationCloser = applicationCloser;
    }

    /**
     * Resolves a single declaration against this instance's own type-name/structure namespaces --
     * the sole entry point; every other {@code resolve*} method is a private dispatch target reached
     * from here. Delegates to {@link #resolve(SchemaMap.Declaration, Optional)} with no position,
     * for a caller with no {@code TsonSchemaParser}-produced position table to hand back (the vast
     * majority of existing callers, including every hand-built test fixture).
     */
    TypeDefinition resolve(SchemaMap.Declaration declaration) {
        return resolve(declaration, Optional.empty());
    }

    /**
     * Same as {@link #resolve(SchemaMap.Declaration)}, but the resulting {@link TypeDefinition}
     * carries {@code declarationPosition} (typically looked up by the caller in {@code
     * TsonSchemaParser#declarationPositions()} for this exact {@code declaration}) -- "where was
     * this declared" is a property of the declaration itself, so it's attached uniformly here at
     * the one place every resolution path already funnels through, regardless of which internal
     * {@code resolve*} method actually built the result.
     */
    TypeDefinition resolve(SchemaMap.Declaration declaration, Optional<SourcePosition> declarationPosition) {
        TypeDefinition resolved = resolveTypeDef(declaration.name(), declaration.typeDef());
        if (declarationPosition.isPresent()) {
            resolved = resolved.withPosition(declarationPosition);
        }
        Annotations annotations = annotationsOf(declaration.name(), declaration.typeDefAnnotations());
        return annotations.isEmpty() ? resolved : resolved.withAnnotations(annotations);
    }

    /**
     * A declaration's own annotations -- the ones written <em>after</em> {@code =>}, which §6 says annotate
     * the definition. Those written before the name annotate the <em>name</em> instead, and §6 is explicit
     * that a resolver "does not hoist annotations from key to value", so they are not collected here.
     * <b>They are dropped, and that is now a gap</b>: §6 and §8.1 place a name-position annotation on the
     * output schema-map <em>key</em> and preserve it there -- documentation, lifecycle, and the resolver's
     * own derived {@code @alias}/{@code @synthetic} markers all live in that channel. Carrying it needs a
     * name-keyed structure this model does not have ({@code BACKLOG.md}).
     *
     * <p>A value is bound through the governing meta the same way §6 describes reading one: the annotation's
     * name resolves one hop against the structure namespace, and its value is read by that type's own
     * compiled reader -- so {@code @doc:"..."} arrives as a {@code String}. <b>A name that does not resolve
     * there is the author's error</b> ({@link #unresolvedAnnotation}), not a silently valueless annotation:
     * §3.3.3's one hop is the whole annotation namespace of a schema document, and an annotation whose type
     * is unreachable has no contract to validate its value against. The case worth its own wording is the
     * one an author actually hits -- a type this schema declares itself, or brings in through {@code
     * !!import}, which §3.3.3 admits for the schema's <em>data documents</em> and excludes here.
     *
     * <p>The check is skipped entirely by a resolver constructed with no {@link AnnotationValueReader}: there
     * is no compiled governing meta to resolve against, so every name would fail it. That is the meta-kernel
     * bootstrap, which is producing the very entries such a reader would read through; there the annotation's
     * name is kept and its value dropped, {@code schema.meta} being a pure value model with no unbound form
     * to hold one in, and dropping the name too would lose more ([TSON-DATA] §1.5).
     */
    Annotations annotationsFor(String name, List<io.ltr8.tson.compiler.ast.Annotation> written) {
        return annotationsOf(name, written);
    }

    private Annotations annotationsOf(String name, List<io.ltr8.tson.compiler.ast.Annotation> written) {
        if (written.isEmpty()) {
            return Annotations.empty();
        }
        Annotations.Builder annotations = new Annotations.Builder();
        for (io.ltr8.tson.compiler.ast.Annotation annotation : written) {
            // The name is checked whether or not a value was written: §6's bare `@T` is shorthand for `@T:_`,
            // so both forms name a type, and a marker whose type nothing can reach is as unresolved as a
            // valued one.
            if (annotationsResolve && metaDefinitions.getTypeDefinition(annotation.name()) == null) {
                throw unresolvedAnnotation(name, annotation.name());
            }
            annotations.add(new Annotation(annotation.name(), annotation.value().flatMap(
                    value -> Optional.ofNullable(bindAnnotationValue(name, annotation.name(), value)))));
        }
        return annotations.build();
    }

    /**
     * Checks a name the author wrote against the kernel's {@code identifier} contract (§7.1's name profile).
     * The resolver builds {@code record_field.name} and its kin directly rather than round-tripping the
     * resolved model through the compiled meta reader, so the type these positions carry -- {@code
     * field_name}, an alias of {@code identifier} -- would otherwise constrain only the positions that *are*
     * read back as data, which is constructor applications and materialisation. Calling it here is what
     * makes one contract reach every naming position rather than the subset the model happens to round-trip.
     */
    private static void requireIdentifier(String name, String role) {
        try {
            IdentifierParser.validate(name);
        } catch (AtomTypeException e) {
            throw new TsonSchemaValidationException("invalid " + role + " -- " + e.getMessage());
        }
    }

    /**
     * §3.3.3's one hop missed: {@code annotationName} is not an entry of the governing meta-schema's own
     * namespace. Worded from the two ways an author gets here -- a name they declared in this very schema (or
     * imported into it), which is the near miss the rule actually catches, and a name that is simply nowhere.
     */
    private TsonSchemaValidationException unresolvedAnnotation(String declaration, String annotationName) {
        boolean local = namespaceDefinitions.getTypeDefinition(annotationName) != null;
        return new TsonSchemaValidationException("'" + declaration + "': '@" + annotationName + "' does not name "
                + "a type in the governing meta-schema's namespace, which is the whole annotation namespace of a "
                + "schema document (one hop through !!meta, §3.3.3)"
                + (local ? " -- the name is declared by this schema or brought in by !!import, which makes it "
                        + "usable by this schema's data documents but not within the schema document itself; "
                        + "declare the annotation type in a meta-schema and point !!meta at that"
                        : ""));
    }

    /** An annotation's value through the type its name refers to, or {@code null} when that type is out of reach. */
    private Object bindAnnotationValue(String declaration, String annotationName, DataValue value) {
        if (metaDefinitions.getTypeDefinition(annotationName) == null) {
            return null;
        }
        try {
            return annotationValueReader.read(annotationName, value);
        } catch (TsonReadException e) {
            // Same split as bindAtomInstance, for the same reason: an annotation value that does not conform
            // to the type its name refers to (§6) is the author's error, and relabelling it a coverage gap
            // aborts the run over a typo.
            throw new TsonSchemaValidationException("'" + declaration + "': the value of annotation '@"
                    + annotationName + "' is not valid data for the type '" + annotationName + "' names -- "
                    + e.getMessage(), e);
        } catch (TsonBindMismatchException e) {
            // The same arm {@link #bindAtomInstance} carries, for the same reason and it is not a stylistic
            // echo: an annotation naming a type the consumer never bound -- the kernel's own `data` among
            // them -- is their configuration, and `TsonMissingBindingException` exists precisely so that a
            // missing line of wiring does not read as "this library cannot do that". Letting the catch-all
            // below have it rebuilds the shape that type was introduced to retire.
            String where = "'" + declaration + "': " + e.getMessage();
            throw e instanceof TsonMissingBindingException ? new TsonMissingBindingException(where)
                    : new TsonBindMismatchException(where);
        } catch (RuntimeException e) {
            throw new UnsupportedOperationException("'" + declaration + "': failed to bind the value of "
                    + "annotation '@" + annotationName + "' via the compiled meta-schema reader: "
                    + e.getMessage(), e);
        }
    }

    private TypeDefinition resolveTypeDef(String name, TypeDef typeDef) {
        if (typeDef instanceof StructuralTypeDef structural) {
            List<String> parameters = structural.typeParams();
            if (structural.body() instanceof RecordDef recordDef) {
                RecordBody body = resolveRecordBody(recordDef.entries(), parameters);
                // Only an error placeholder is both parameterised and still a bare RecordDef here -- the
                // desugar phase rewrote every real record template into the `!record { ... }` §5.2 says it
                // denotes. Holding it anyway is what leaves no parameterised RecordBody anywhere, so
                // materialisation needs only the one substitution path. See WireForm.heldEmptyRecord.
                return holdIfOpen(name, new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, parameters,
                        List.of(), List.of(), Optional.empty(), body));
            }
            if (structural.body() instanceof ConstructionDef construction) {
                return holdIfOpen(name, resolveComposition(name, construction, parameters));
            }
            if (structural.body() instanceof RefinedDef refined) {
                return holdIfOpen(name, resolveRefinement(name, refined, parameters));
            }
        }
        if (typeDef instanceof ReferenceTypeDef referenceTypeDef) {
            // A parameter list here is §5.10's partial application -- `uuid_pair => <B> pair<uuid, B>`, a
            // reference that leaves parameters open and re-declares them, which makes the alias itself a
            // template. It threads straight through; substituting into the recorded application is
            // materialisation's job, and until then the open form is what the entry records.
            List<String> parameters = referenceTypeDef.typeParams();
            if (referenceTypeDef.ref() instanceof SimpleRef simple) {
                return TypeDefinition.reference(io.ltr8.tson.schema.meta.TypeRef.of(simple.name()), parameters);
            }
            if (referenceTypeDef.ref() instanceof GenericRef generic) {
                return resolveTemplateApplication(name, generic, parameters);
            }
        }
        // A declaration-level container form is rewritten by SchemaDesugarer before resolution -- a sized
        // array into the `!array { ... }` construction it denotes with min_items/max_items bound directly
        // (§5.3), a size-less one into the same without them (§5.6), and a tuple into `!tuple { ... }`.
        // A nested bracket form at either position is expanded there too, innermost first. Anything still
        // here holds an optional array element (`[T?]`), the one shape that phase does not build, and falls
        // through below.
        if (typeDef instanceof Instance instance) {
            // The parameter list is the whole difference: with one, the payload is held rather than bound,
            // and stays that way until materialisation substitutes it away (§5.10).
            return instance.typeParams().isEmpty() ? resolveInstance(name, instance)
                    : resolveInstanceTemplate(name, instance);
        }
        if (typeDef instanceof AtomRefinement refinement) {
            return resolveAtomRefinement(name, refinement);
        }
        throw new UnsupportedOperationException(
                "'" + name + "': only fresh record constructions, composition, simple type references, "
                        + "declaration-level sized arrays, constructor application, and atom refinement are "
                        + "resolved so far, got " + typeDef.getClass().getSimpleName());
    }

    /**
     * A composition or refinement template's body, <b>held</b> like every other open body -- so that one
     * process closes them all and {@code record_field.value_param} has one fewer producer.
     *
     * <p><b>Why these two are held here and a plain record body at desugar.</b> Both absorb fields from a
     * source (§5.8's supertypes, §5.7's refinement source), and the form to hold is the <em>flattened</em>
     * one: a §5.7 tightening entry states a modifier and no type-ref, which is not a {@code record_field} at
     * all until the inherited field supplies one. So the rewrite has to happen where a namespace is in hand,
     * which is here -- but the <em>spelling</em> stays {@code SchemaDesugarer}'s, through {@link WireForm#heldRecord}, because two spellings of the held form is the one thing this design cannot
     * survive.
     *
     * <p>Only a record-shaped body: a parameterized atom refinement is not a form §12.1 admits, and an atom
     * body has no parameters to hold open.
     */
    private TypeDefinition holdIfOpen(String name, TypeDefinition resolved) {
        if (resolved.parameters().isEmpty() || !(resolved.body() instanceof RecordBody record)) {
            return resolved;
        }
        if (record.fields().isEmpty() && record.groups().isEmpty() && record.supertypes().isEmpty()) {
            return resolved.withBody(new HeldBody(WireForm.heldEmptyRecord()));
        }
        return resolved.withBody(new HeldBody(WireForm.heldRecord(record,
                value -> annotationWireValue(name, value))));
    }

    /**
     * One resolved annotation's bound value back in wire form. The object writer is the right tool for
     * exactly this leaf and no more of the body: unbinding is what it does, and an annotation value is a
     * self-contained value rather than part of the spelling the held form has to keep.
     */
    private DataValue annotationWireValue(String name, Object value) {
        try {
            return new TsonDataParser(writer.toTson(value)).parseDocument().root();
        } catch (RuntimeException e) {
            throw new UnsupportedOperationException("'" + name + "': failed to re-serialize an annotation "
                    + "value while holding the template's body: " + e.getMessage(), e);
        }
    }

    // ── Constructor application (§5.5, §5.6) ────────────────────────────────

    /**
     * Whether {@code !C { ... }} may apply {@code C} at all: <b>{@code C} IS-A {@code top}</b>
     * ([TSON-SCHEMA] §4.1), read off the transitive supertype chain.
     *
     * <p><b>This replaces asking whether {@code C} is a constructor, and it is a wider and more exact
     * question.</b> §4.1 makes every base kind IS-A {@code top} and every constructor transitively so, while
     * IS-A stops at construction -- an instance or a fresh record carries an empty chain. So the predicate
     * admits every constructor, and beyond them exactly the entries that describe <em>a type</em> rather than
     * a part of one.
     *
     * <p>What it lets in that {@code constructor} did not:
     * <ul>
     *   <li>{@code reference}, which the kernel deliberately leaves unmarked because it describes no value,
     *   and which the language nonetheless needs applicable. It used to take a by-name exception in {@link
     *   #resolveTemplateInstance} and none here, so {@code <T> !reference { target: T }} resolved while
     *   {@code !reference { target: int32 }} did not -- one construction legal open and illegal closed.</li>
     *   <li>the four base kinds, which cost nothing: each is an abstract union whose own reader refuses a
     *   direct application by naming the subtypes that would satisfy it.</li>
     * </ul>
     *
     * <p>What it keeps out is the set that matters -- {@code record_field}, {@code type_ref}, {@code
     * type_argument}, {@code tuple_element}, {@code field_group}, {@code integer_size}, {@code
     * atom_specification}, {@code type_definition}: record-bodied entries with empty chains, every one a
     * component of a type rather than a type. Without a check they fail anyway, on {@code Top} being sealed,
     * but as a {@code ClassCastException} reported {@code NOT_IMPLEMENTED} -- a non-verdict, for an author
     * error.
     */
    private static void requireApplicable(String name, String target, TypeDefinition applied) {
        if (applied.supertypes().contains(TOP)) {
            return;
        }
        throw new TsonSchemaValidationException("'" + name + "': '!" + target + "' is not applicable -- it is "
                + "not IS-A 'top' (§4.1), so it describes a part of a type rather than a type, and there is "
                + "nothing for '!" + target + " { ... }' to build. Did you mean atom refinement ('!" + target
                + " ^ { ... }')?");
    }

    /**
     * {@code !C value} (constructor application, no {@code ^}) -- produces a fresh instance filled
     * with {@code value}.
     *
     * <p>{@code C} resolves against the structure namespace only (see {@link #resolveConstructorTarget}),
     * and faces two questions. It MUST be <b>applicable</b> -- IS-A {@code top} (§4.1), see {@link
     * #requireApplicable} -- and it MUST be <b>closed</b>: a template ({@code C} declaring type parameters)
     * closes by application, {@code C<...>}, never by construction, so naming one here is a resolver error
     * whether or not it also carries {@code ~}.
     *
     * <p>{@code value} is bound via {@link #bindAtomInstance} directly -- {@code instance.value().typeRef()}
     * already names {@code C} (per {@code Instance}'s own reshape, matching §12.1's {@code instance = "!"
     * type-name ws core-value}); positional form (§5.6) and schema-composed defaults (§5.2/§5.7) are handled
     * uniformly by the compiled {@code Record*Reader} itself (see {@code RecordAbstractReader}'s own
     * Javadoc), not by a separate normalization step here. Construction transfers only {@code C}'s {@code
     * kind} (§5.5): no supertypes, no parameters, {@code constructor: false} on the result -- except for an
     * alias, where the result is the entry {@code name => X} denotes, since {@code !reference { target: X }}
     * is that alias written out (§8.3).
     *
     * <p>Binds against {@link Top}, not the narrower {@code Atom} -- some constructors (e.g. {@code
     * unknown_type => ~sum & {}}) compose with {@code sum}, not {@code atom}. {@code C}'s own body is
     * always a {@link RecordBody} by the time it is read, which the two questions above establish rather
     * than check: see the note at the binding site.
     */
    private TypeDefinition resolveInstance(String name, Instance instance) {
        String target = instance.target();
        TypeDefinition constructor = resolveConstructorTarget(name, target);
        if (!constructor.parameters().isEmpty()) {
            int declared = constructor.parameters().size();
            throw new TsonSchemaValidationException("'" + name + "': '" + target + "' is a template taking "
                    + declared + " type argument" + (declared == 1 ? "" : "s") + " " + constructor.parameters()
                    + ", and a template closes by application, not by construction -- write '" + target
                    + "<...>' with its arguments (§5.10). '!" + target + " { ... }' fills a constructor's own "
                    + "vocabulary (§4.2), which is a different operation and needs a closed constructor");
        }
        requireApplicable(name, target, constructor);
        // No check that the head's own body is a record: the two above leave nothing else. `supertypes` is
        // populated only by composition and refinement, both of which build a `RecordBody` -- except where
        // `holdIfOpen` wraps one for an open declaration, and a declaration with parameters is refused as a
        // template above. Every other body shape (a construction's bound value, an alias's `Reference`)
        // comes with an empty chain, since IS-A stops at construction (§4.1), so `requireApplicable` refuses
        // it. Parameters empty and IS-A `top` therefore imply a record body.
        Top body = bindAtomInstance(name, instance.value());
        if (body instanceof io.ltr8.tson.schema.meta.Reference reference) {
            // `!reference { target: X }` is the explicit spelling of the alias `name => X` (§8.3), so it
            // denotes the same entry: `kind: REFERENCE` (§4.1 -- a type_kind, not one the supertype chain
            // could give) with `X` as both source and body. Dispatched on the *body* rather than on the head's
            // name because this path has already read it; `resolveInstanceTemplate` holds its body unread and
            // so has only the name to go on, which is what its own `alias` flag is for.
            return TypeDefinition.reference(reference.target());
        }
        return new TypeDefinition(Optional.of(io.ltr8.tson.schema.meta.TypeRef.of(target)), constructor.kind(),
                List.of(), List.of(), List.of(), Optional.empty(), body);
    }

    /**
     * {@code <T, N> !array { element_type: T  min_items: N }} -- the open counterpart of {@link
     * #resolveInstance}. The target resolves the same way and must be a constructor for the same reason; what
     * differs is that the payload is <b>not read through that constructor's reader at all</b>. At least one
     * binding stands for a parameter and no parameter is an {@code integer}, so the body is held as written
     * ({@link HeldBody}) and bound once, at materialisation, when substitution has left no parameter in it.
     *
     * <p><b>Holding is what removes the collection boundary.</b> A typed open vocabulary has to spell a
     * parameter per slot kind, and has no spelling for one inside a collection -- so {@code <T> !choice
     * { variants: [T error] }} had no representation and was refused where it was written. Held, it is a
     * token inside an array, and the phase that would have had to classify it does not run.
     *
     * <p><b>What is still checked here is what does not depend on the parameters</b> (§5.10). Two structural
     * questions can be answered from the binding record's own field names, with no stand-in values and so no
     * risk of failing a template that is correct for every argument anyone passes: that each name is a field
     * the constructor declares, and that every REQUIRED-without-default field of it is bound by something.
     * Both are conditions no application could repair, which makes the declaration the right place to report
     * them. Everything value-shaped -- the typing of each binding, the family coherence rules -- waits for
     * materialisation, where the whole body binds through the constructor's own reader at once.
     *
     * <p>A positional payload (§5.6) carries no field names, so neither check applies to it; the reader
     * settles it at materialisation like any other positional form.
     */
    private TypeDefinition resolveInstanceTemplate(String name, Instance template) {
        String target = template.target();
        TypeDefinition constructor = resolveConstructorTarget(name, target);
        // `reference` needs no exception here any more: it IS-A `top`, so the generic rule admits it, which
        // is what makes the open and closed spellings of one construction agree. Its `kind` still cannot come
        // from its supertype chain -- §4.1 gives an alias `kind: REFERENCE`, a type_kind and not a base kind
        // -- so that fact alone stays the kernel's own, below.
        boolean alias = REFERENCE.equals(target);
        requireApplicable(name, target, constructor);
        if (!(constructor.body() instanceof RecordBody vocabulary)) {
            throw new IllegalStateException("'" + name + "': constructor '" + target + "' has a "
                    + constructor.body().getClass().getSimpleName() + " body; a constructor is record-shaped "
                    + "(§7.2) and cannot be declared otherwise");
        }
        if (template.value().coreValue() instanceof RecordValue bindings) {
            checkTemplateBindings(name, target, vocabulary, bindings);
        }
        return new TypeDefinition(Optional.of(io.ltr8.tson.schema.meta.TypeRef.of(target)),
                alias ? TypeKind.REFERENCE : constructor.kind(),
                template.typeParams(), List.of(), List.of(), Optional.empty(),
                new HeldBody(template.value()));
    }

    /** §5.10's two declaration-time questions about a held binding record -- see {@link #resolveInstanceTemplate}. */
    private static void checkTemplateBindings(String name, String target, RecordBody vocabulary,
            RecordValue bindings) {
        Set<String> bound = new LinkedHashSet<>();
        for (RecordValue.Field binding : bindings.fields()) {
            if (vocabulary.fields().stream().noneMatch(field -> field.name().equals(binding.name()))) {
                throw new TsonSchemaValidationException("'" + name + "': '" + target + "' has no field '"
                        + binding.name() + "' to bind (§7.2) -- its fields are "
                        + vocabulary.fields().stream().map(RecordField::name).toList());
            }
            bound.add(binding.name());
        }
        for (RecordField field : vocabulary.fields()) {
            if (field.state() == FieldState.REQUIRED && field.value().isEmpty() && !bound.contains(field.name())) {
                // No application of this template could ever produce a valid instance, so the template is
                // wrong wherever the application is -- exactly the case the declaration is the right place
                // to report.
                throw new TsonSchemaValidationException("'" + name + "': '" + target + "' requires a '"
                        + field.name() + "', and nothing binds it (§7.2), so no application of this template "
                        + "could build one");
            }
        }
    }

    // ── Atom refinement (§5.5, §5.7) ─────────────────────────────────────────

    /**
     * {@code !I ^ { values }} -- refines an atom-family instance by tightening its constructor's
     * constraint fields. Per §3.3.1, {@code I} resolves against the type-name namespace *only*
     * (never the structure namespace -- unlike {@link #resolveInstance}'s {@code C}) and MUST be a
     * non-constructor instance of an atom family (kind {@code ATOM}, {@code constructor: false}).
     * The constructor {@code I} was built from is reached through {@code I}'s own {@code source}
     * field -- never a further name lookup, so refinement works even where the constructor itself
     * isn't name-visible (e.g. a governed schema that can't name {@code integer_type} directly, only
     * {@code integer}).
     *
     * <p>Unlike {@link #resolveInstance}, {@code refinement.bindings()}'s own {@code typeRef} is not
     * pre-set by the grammar -- §12.1's {@code atom-refinement} takes a bare {@code record-def}, naming no
     * constructor -- so this attaches {@code I}'s constructor name to the value's type-ref
     * itself before binding through {@link #bindAtomInstance}. No positional-form wrapping (unlike
     * {@code Instance}) -- §5.5 guarantees a refinement body is always a braced record; {@link
     * #mergeWithSource} rejects anything else.
     *
     * <p><b>Merges with {@code I}'s own already-bound value; does not replace it</b> ({@link
     * #mergeWithSource}) -- required for a *chained* refinement to behave correctly: given {@code
     * int8 => !integer ^ { size: {...} } }, {@code big => !int8 ^ { min: -500 } } MUST still carry
     * {@code int8}'s own {@code size}, since {@code big} is declared as a refinement -- a narrowing --
     * of {@code int8}, and §5.7's own "Body materialisation" rule for the structurally analogous
     * record-refinement case is explicit that inherited fields survive a refinement that doesn't
     * mention them. A field named in the new refinement's own {@code values} overrides {@code I}'s
     * own value for it; any field {@code I} itself bound that {@code values} doesn't mention keeps
     * {@code I}'s own value.
     *
     * <p>Per §5.5's own text (not the general composition/refinement induction of §5.7/§5.8):
     * {@code source} is {@code I}'s own constructor ({@code I.source()}, e.g. {@code integer_type}
     * for {@code I = integer}), and {@code supertypes} is the literal single-element {@code [I]} --
     * not transitively chained with {@code I}'s own supertypes (empty for every fresh {@code
     * Instance}).
     */
    private TypeDefinition resolveAtomRefinement(String name, AtomRefinement refinement) {
        String sourceName = refinement.target();
        TypeDefinition source = namespaceDefinitions.getTypeDefinition(sourceName);
        if (source == null) {
            throw new TsonSchemaValidationException("'" + name + "': '!" + sourceName
                    + "' does not resolve against the type-name namespace (§3.3.1)");
        }
        // §5.5's question, in the type system's own terms: is this an atom *instance*? An atom-kinded entry
        // that is not itself applicable is exactly one -- §4.1's "IS-A does not extend below construction"
        // is what makes the pair separable, since `!T {}` transfers kind and not supertypes, so `integer`
        // carries an empty chain where `integer_type => ~atom & { ... }` carries `[atom, top]`.
        //
        // Note which way round that runs: IS-A `atom` is true of the *constructor* and false of every
        // instance, so it is the constructors it selects. Kind alone does not separate them either --
        // `integer_type` is ATOM-kinded exactly like its instances. It takes both halves.
        if (source.kind() != TypeKind.ATOM || source.supertypes().contains(TOP)) {
            // The construction hint is offered only where construction would actually work, which is the
            // same applicability question (§4.1) -- so `top`, not applicable, gets the plain answer rather
            // than advice that would fail in turn.
            throw new TsonSchemaValidationException("'" + name + "': '!" + sourceName + " ^ { ... }' needs an "
                    + "atom-family instance to narrow (§5.5), and '" + sourceName + "' is "
                    + (source.supertypes().contains(TOP)
                            ? "a constraint vocabulary -- '^' narrows one of its instances. Did you mean "
                                    + "constructor application ('!" + sourceName + " { ... }')?"
                            : "kind=" + source.kind() + ", which has no atom constraints to tighten"));
        }
        // Not an author error, unlike the three checks above: an atom-family instance always records the
        // constructor it came from, so one that doesn't is a malformed TypeDefinition, not a schema anyone
        // wrote. It stays a library-fault type so it propagates rather than being reported as a verdict on
        // the author's schema.
        io.ltr8.tson.schema.meta.TypeRef constructorRef = source.source().orElseThrow(() ->
                new UnsupportedOperationException("'" + name + "': '!" + sourceName
                        + "' has no recorded constructor to refine through"));

        DataValue merged = mergeWithSource(name, source.body(), refinement.bindings(), constructorRef.name());
        Top body = bindAtomInstance(name, merged);
        checkNarrows(name, sourceName, source.body(), body);

        return new TypeDefinition(Optional.of(constructorRef), source.kind(), List.of(),
                List.of(sourceName), List.of(), Optional.empty(), body);
    }

    /**
     * §5.7's tightening rule, enforced: a refinement narrows its source's constraints, so a body
     * field that <em>loosens</em> one is a resolver error rather than a silently accepted override.
     * Without this, {@code !uint8 ^ { min: -10  max: 300 } } resolves happily to something wider
     * than the {@code uint8} it claims to refine.
     *
     * <p>Runs on the two bound constraint objects -- the source's own body and the merged result --
     * and asks the family itself, via {@link Atom#constraintsCheck}, since only it knows what "more
     * constrained" means for its own fields. Comparing the merged result rather than the refinement
     * body alone is what makes the check work for a facet the body never mentioned: an inherited
     * facet compares equal to the source's and tightens vacuously, so only what the author actually
     * wrote can fail.
     *
     * <p>A non-{@link Atom} body is not reachable here -- {@link #resolveAtomRefinement} has already
     * rejected a source that isn't an atom-family instance, and the merged value binds through that same
     * source's own constructor. The guard below returns rather than throwing for that reason: there is no
     * narrowing question to ask, and a body that reached here anyway is one of the two constructors'
     * problem, not this check's.
     */
    private void checkNarrows(String name, String sourceName, Top sourceBody, Top refinedBody) {
        if (!(sourceBody instanceof Atom sourceAtom) || !(refinedBody instanceof Atom refinedAtom)) {
            return;
        }
        List<String> violations = sourceAtom.constraintsCheck(refinedAtom);
        if (!violations.isEmpty()) {
            throw new TsonSchemaValidationException("'" + name + "': not a valid refinement of '!" + sourceName
                    + "' (§5.7): " + String.join("; ", violations));
        }
    }

    /**
     * §5.7's "Body materialisation" rule, applied to atom refinement (§5.6's chained-refinement merge):
     * {@code newBindings} merged *over* {@code sourceBody}'s own
     * already-bound fields, not replacing them. {@code sourceBody} is re-serialized back to plain
     * record wire form via {@code TsonObjectWriter.toTson} (writing a {@code Top}-typed value by its
     * own runtime class never emits a type-ref -- exactly the plain-record shape wanted here) and
     * re-parsed, so this needs no per-atom-class merge logic -- it works generically for every
     * atom-constraint class the same way. Field merge is by name at the {@link RecordValue} level:
     * {@code newBindings}'s own fields win; anything only {@code sourceBody} had survives untouched.
     *
     * <p><b>Merging before binding, not after, is required.</b> Binding {@code newBindings} on its own
     * and merging the two constraint objects would fail for any constructor with a {@code REQUIRED}
     * field carrying no schema default -- {@code float_type.format}, {@code binary.encoding} -- since
     * the refinement body has no reason to restate a facet its source already fixed, and the reader
     * would report {@code FIELD_REQUIRED} with nothing to fall back on. Merging first means the record
     * that reaches the reader is always complete.
     *
     * <p>Widening is caught after binding, by {@link #checkNarrows}, rather than here: this merge is
     * deliberately blind, and the two bound constraint objects are what a family's own narrowing rule
     * can actually compare.
     */
    private DataValue mergeWithSource(String name, Top sourceBody, DataValue newBindings, String constructorName) {
        Map<String, RecordValue.Field> merged = new LinkedHashMap<>();
        if (sourceSerializedFields(name, sourceBody) instanceof RecordValue sourceRecord) {
            for (RecordValue.Field field : sourceRecord.fields()) {
                merged.put(field.name(), field);
            }
        }
        if (newBindings.coreValue() instanceof RecordValue newRecord) {
            for (RecordValue.Field field : newRecord.fields()) {
                merged.put(field.name(), field);
            }
        } else if (!(newBindings.coreValue() instanceof EmptyBrace)) {
            // The author's error, not a gap: §12.1's `atom-refinement` takes a `record-def`, so this verdict
            // does not change as this library improves. Unreachable from source -- `TsonSchemaParser` refuses
            // a non-braced body at the `^` -- but the resolver is also driven directly, and coding it
            // NOT_IMPLEMENTED would exit 70 over a construct the grammar itself refuses.
            throw new TsonSchemaValidationException("'" + name + "': expected a braced record of constraint "
                    + "bindings (§5.5), found " + newBindings.coreValue());
        }
        return new DataValue(newBindings.annotations(), Optional.of(constructorName), new RecordValue(List.copyOf(merged.values())));
    }

    private CoreValue sourceSerializedFields(String name, Top sourceBody) {
        try {
            String sourceText = writer.toTson(sourceBody);
            return new TsonDataParser(sourceText).parseDocument().root().coreValue();
        } catch (TsonWriteException e) {
            throw new UnsupportedOperationException(
                    "'" + name + "': failed to re-serialize the refinement source: " + e.getMessage(), e);
        }
    }

    /**
     * A constructor-application target ({@code !C value}) resolves against {@code metaDefinitions}
     * (the structure namespace) only -- never {@code namespaceDefinitions} (the type-name namespace).
     * A constructor is always meta-schema vocabulary (a {@code type_definition} with {@code
     * constructor: true}, e.g. {@code integer_type}/{@code enum}), never something a schema
     * legitimately defines about itself and, in the same pass, instantiates -- the target is always
     * declared in the *governing* meta-schema, one hop via {@code !!meta}, so the structure namespace
     * alone is enough.
     */
    private TypeDefinition resolveConstructorTarget(String name, String target) {
        TypeDefinition structural = metaDefinitions.getTypeDefinition(target);
        if (structural != null) {
            return structural;
        }
        throw new TsonSchemaValidationException("'" + name + "': '!" + target
                + "' does not resolve against the structure namespace (§3.3.1)");
    }

    /**
     * Shared by {@link #resolveInstance} and {@link #resolveAtomRefinement} -- both need to read a
     * type-ref-carrying value against its own constructor's compiled reader. {@code value} already
     * names its own constructor via {@link DataValue#typeRef()}, so {@code
     * definitionMetaReader.read(constructorName, value)} finds and reads the right compiled reader
     * directly -- no separate name→class table, no union-member scan.
     */
    private Top bindAtomInstance(String name, DataValue value) {
        String constructorName = value.typeRef().orElseThrow(() -> new IllegalStateException(
                "'" + name + "': normalized value has no type-ref naming its own constructor -- "
                        + "DefinitionResolver should never produce this"));
        Top body;
        try {
            body = definitionMetaReader.read(constructorName, value);
        } catch (TsonReadException e) {
            throw bodyIsNotValidData(name, constructorName, e);
        } catch (TsonBindMismatchException e) {
            // The constructor is a meta layer's own and the consumer never registered a class for it, or
            // registered one that disagrees. Either way it is their configuration, and it already says so --
            // wrapping it as an UnsupportedOperationException would relabel it a library gap, which is the
            // classification a caller acts on. Rethrown whole, naming the declaration that applied it, and
            // keeping which of the two it is -- a type with no class at all reads differently from one whose
            // class disagrees, and the caller's next move differs with it.
            String where = "'" + name + "': " + e.getMessage();
            throw e instanceof TsonMissingBindingException ? new TsonMissingBindingException(where)
                    : new TsonBindMismatchException(where);
        } catch (TsonSchemaValidationException e) {
            // A constructor's own record refusing the values it was handed. `decimal_type`'s member set is the
            // case: `members` is typed `set<value>`, so the wire admits anything and the family itself is what
            // says a member must be a number the atom reads. The author's error either way -- the verdict on
            // `members: ["abc"]` does not change when this library improves -- so it keeps its classification
            // rather than being relabelled a gap by the catch-all below.
            throw new TsonSchemaValidationException("'" + name + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new UnsupportedOperationException(
                    "'" + name + "': failed to bind '" + constructorName + "' via the compiled meta-schema reader: "
                            + e.getMessage(), e);
        }
        checkCoherent(name, constructorName, body);
        return body;
    }

    /**
     * Every atom body this resolver produces is checked for self-coherence, here rather than at
     * either call site, because this is the one place both of them meet: {@link #resolveInstance}'s
     * {@code !C value} and {@link #resolveAtomRefinement}'s {@code !I ^ { ... }} bind through this
     * method and nothing else does.
     *
     * <p>Both base kinds that carry orderable facets are asked, each by its own rule: {@link
     * Atom#coherenceCheck} for a constraint vocabulary's {@code min}/{@code max} family, {@link
     * Product#coherenceCheck} for a container's {@code min_items}/{@code max_items} pair. A {@link
     * RecordBody}, a {@link TupleBody} or a sum has no such pair and passes straight through on the
     * default. The rule is the family's own in both cases, since only it knows which of its fields form a
     * range -- and asking them here rather than one per kind is what lets a body with both kinds of
     * incoherence report both in one pass.
     *
     * <p>A violation is the <b>author's</b> error and stays a {@link TsonSchemaValidationException}:
     * the verdict on {@code { min_length: 10  max_length: 3 }} does not change when this library
     * improves. It is deliberately not left to the atom parsers, which would surface it as an {@code
     * ErrorReader} -- the library-gap marker -- and so would give exactly the wrong classification.
     *
     * <p>Running it after binding rather than on the wire record is what makes it work generically:
     * a facet arrives here already converted to the host type its family compares on, and a facet the
     * body never mentioned is already filled in from the constructor's own schema-composed default.
     */
    private static void checkCoherent(String name, String constructorName, Top body) {
        List<String> violations = switch (body) {
            case Atom atom -> atom.coherenceCheck();
            case Product product -> product.coherenceCheck();
            case Sum sum -> sum.coherenceCheck();
            default -> List.of();
        };
        if (!violations.isEmpty()) {
            throw new TsonSchemaValidationException("'" + name + "': the body's own '" + constructorName
                    + "' constraints contradict each other: " + String.join("; ", violations));
        }
    }

    /**
     * A body the constructor's own vocabulary rejects is the <b>author's</b> error, not a coverage gap
     * (§7.2: a constructor "is a record-shaped type, so it validates a record against its constraint-field
     * vocabulary", receiving ordinary record validation) -- so it is a {@link TsonSchemaValidationException},
     * which {@code SchemaResolver} collects into a diagnostic and carries on from, rather than an {@link
     * UnsupportedOperationException}, which aborts the run under the "this is a bug in tson" banner. Both a
     * wrong-typed member ({@code !integer ^ { min: "abc" }}) and an unknown one ({@code minimum}) arrive here.
     *
     * <p>The {@link TsonReadException}'s own {@link io.ltr8.tson.compiler.Diagnostic} is deliberately
     * discarded and only its message kept: it was produced against a {@code DataValueEvents} replay of an
     * already-parsed AST, whose positions are all the {@code (0,0,0)} placeholder and whose {@code path} is a
     * data pointer into a synthetic body. Carrying those into a schema diagnostic would furnish a schema-side
     * problem with confident-looking data-side locations that name nothing. The declaration's own position and
     * {@code schemaPointer} come from {@code SchemaResolver}'s catch instead, which is where they are real.
     */
    private static TsonSchemaValidationException bodyIsNotValidData(String name, String constructorName,
                                                                    TsonReadException cause) {
        return new TsonSchemaValidationException("'" + name + "': the body is not valid data for '"
                + constructorName + "', the constructor's own constraint vocabulary -- " + cause.getMessage(), cause);
    }

    // ── Top-level constructor application (§5.6) ──────────────────────────

    /**
     * A declaration whose body is an application that {@code SchemaDesugarer} did not rewrite -- in practice
     * a <em>template</em> application, since every constructor application is turned into an {@code !C value}
     * instance before resolution. It resolves to a {@link TypeKind#REFERENCE} entry targeting the application
     * as written. The arguments are carried, not applied: closing the application is
     * {@code TemplateMaterialiser}'s pass, which runs over the resolved form.
     *
     * <p>{@code parameters} is the declaration's own {@code <...>} list, empty for an ordinary alias and
     * non-empty for §5.10's <b>partial application</b> ({@code uuid_pair => <B> pair<uuid, B>}), where some
     * of the arguments name parameters this declaration re-declares. It threads through untouched -- what
     * makes the entry a template is exactly that list, and the open form is the application itself.
     */
    private TypeDefinition resolveTemplateApplication(String name, GenericRef generic, List<String> parameters) {
        List<TypeArgument> arguments = new ArrayList<>();
        for (TypeArg arg : generic.args()) {
            try {
                arguments.add(typeArgument(arg));
            } catch (UnsupportedOperationException e) {
                throw new UnsupportedOperationException("'" + name + "': " + e.getMessage());
            }
        }
        return TypeDefinition.reference(new io.ltr8.tson.schema.meta.TypeRef(generic.name(), arguments),
                parameters);
    }

    /**
     * One argument of an application as the {@code type_argument} it denotes: a literal keeps its own token
     * form, and a reference resolves through {@link #resolveTypeRef}, so an argument may itself be an
     * application ({@code box<box<text>>}). §12.1 defers the reference/literal classification to this layer,
     * and the grammar has already made it -- a quoted or numeric token parsed as a {@link TypeArg.Value}.
     */
    private TypeArgument typeArgument(TypeArg arg) {
        if (arg instanceof TypeArg.Value value) {
            return new TypeArgument.Value(new Token(value.value().text(), tokenForm(value.value().form())));
        }
        return new TypeArgument.Ref(resolveTypeRef(((TypeArg.Ref) arg).ref()));
    }

    private static Token.Form tokenForm(TokenForm form) {
        return switch (form) {
            case UNQUOTED -> Token.Form.UNQUOTED;
            case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
        };
    }

    // ── Composition (§5.8) and subtraction (§5.9) ─────────────────────────


    /**
     * {@code A & B & { ... }}: each supertype's fields and groups are copied into the result, left
     * to right (§5.8's field-ordering rule, §5.11's "supertypes contribute their groups whole"),
     * checked for name overlap across supertypes; the trailing body's own entries are then resolved
     * against {@code inheritedFieldIndex} (name -&gt; position in {@code fields}, populated by the
     * supertype loop above) -- a body field naming an inherited field is a *tightening* entry
     * (§5.7, via {@link #resolveTighteningField}) and replaces that field in place; a body field
     * naming nothing inherited is genuinely new and is appended, same as before (§5.8's "new fields
     * are permitted; existing fields may be tightened" and "tightening entries replace inherited
     * fields in place; new fields are appended after all inherited fields"). {@code
     * type_definition.supertypes} accumulates by induction: each supertype's own {@code
     * supertypes()} is already its full transitive chain (by the same induction, computed when
     * *that* entry was resolved), so {@code direct + parent.supertypes()} for every direct
     * supertype, deduplicated, is the complete transitive chain -- no separate graph walk needed.
     * {@code parameters} (a template's own {@code <T, ...>} list, §5.10) threads straight through
     * from the declaration's {@code typeParams} into the result -- {@code array}'s own shape
     * ({@code array => <T> ~product & { ... } }) -- with no substitution or validation that a field
     * actually uses each parameter.
     */
    private TypeDefinition resolveComposition(String name, ConstructionDef construction,
                                               List<String> parameters) {
        List<String> directSupertypes = new ArrayList<>();
        List<String> transitiveSupertypes = new ArrayList<>();
        Set<String> seenTransitive = new HashSet<>();
        List<RecordField> fields = new ArrayList<>();
        List<FieldGroup> groups = new ArrayList<>();
        Set<String> seenFieldNames = new HashSet<>();
        Map<String, Integer> inheritedFieldIndex = new LinkedHashMap<>();

        for (TypeRef supertypeRef : construction.supertypes()) {
            if (supertypeRef instanceof GenericRef generic && namesOwnParameter(generic, parameters)) {
                // §5.8's "Parameterized references" at their open end: the operand is applied to this
                // declaration's own parameter, so it denotes no entry and contributes no name. Its fields
                // come through all the same, and its own supertypes with them -- see openOperand.
                OpenOperand operand = openOperand(name, generic, parameters, "supertype");
                for (String ancestor : operand.ancestors()) {
                    addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
                }
                absorb(name, operand.body(), fields, groups, seenFieldNames, inheritedFieldIndex);
                continue;
            }
            if (supertypeRef instanceof GenericRef generic) {
                // A fully-bound application: closed to the entry it denotes, which is a real name this can
                // index against. Closing is also what gives it a field set to absorb.
                supertypeRef = new SimpleRef(closedApplication(name, generic, parameters, "supertype"));
            }
            if (!(supertypeRef instanceof SimpleRef simple)) {
                // A choice or an inline array/tuple at a supertype position. §12.1 lets these through only
                // because `construction-def` draws its operands from `type-ref` where `refined-def` takes a
                // name -- nothing here could ever denote a record, so there is no field set to compose with
                // and no implementation to wait for; §12.1's `supertype-ref` narrows the operands to named
                // references, so this shape is the grammar's own error to refuse.
                throw new TsonSchemaValidationException("'" + name + "': a "
                        + (supertypeRef instanceof ChoiceRef ? "choice" : "bracketed array/tuple")
                        + " cannot be a supertype -- '&' composes record types, and this form has "
                        + (supertypeRef instanceof ChoiceRef ? "variants" : "elements") + ", not fields (§5.8)");
            }
            String supertypeName = simple.name();
            TypeDefinition supertypeDef = namespaceDefinitions.getTypeDefinition(supertypeName);
            if (supertypeDef == null) {
                // Not a library gap: composition copies the supertype's own fields, so the name has to
                // resolve here rather than being left to the linker the way an ordinary field type is.
                throw new TsonSchemaValidationException("'" + name + "': supertype '" + supertypeName
                        + "' names no type this schema declares or imports");
            }
            if (!(supertypeDef.body() instanceof RecordBody supertypeBody)) {
                // §4.3 generalises §5.7's vocabulary-body requirement to composition, which has the same
                // need: it copies the parent's fields, and a binding record has none to copy.
                throw new TsonSchemaValidationException("'" + name + "': supertype '" + supertypeName
                        + "' has no fields to contribute -- its body is a binding record, not a vocabulary, so "
                        + "there is nothing for '&' to compose with (§5.8, and §5.7's vocabulary-body rule "
                        + "read across). Compose with the head it derives from");
            }

            directSupertypes.add(supertypeName);
            addIfAbsent(transitiveSupertypes, seenTransitive, supertypeName);
            for (String ancestor : supertypeDef.supertypes()) {
                addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
            }

            absorb(name, supertypeBody, fields, groups, seenFieldNames, inheritedFieldIndex);
        }

        if (construction.body().isPresent()) {
            for (RecordEntry entry : construction.body().get().entries()) {
                resolveEntry(name, entry, fields, groups, seenFieldNames, inheritedFieldIndex, parameters);
            }
        }

        if (construction.removal().isPresent()) {
            applyRemovals(name, construction.removal().get(), bodyNames(construction), fields, groups);
        }
        checkGroupPresence(name, fields, groups);

        TypeKind kind = determineKind(name, transitiveSupertypes);
        RecordBody body = new RecordBody(directSupertypes, fields, groups);
        // §5.9: subtraction breaks IS-A. The contract index (type_definition.supertypes) is emptied while the
        // body keeps `directSupertypes` as authorial lineage (record.supertypes) -- the distinction §7.2's
        // subsumption rule reads, so a subtracted type does not stand where its source is expected. `kind` is
        // still taken from the lineage chain: a chain that reached `product` still says what this type *is*,
        // and §4.1's own rule over the now-empty contract index would answer PRODUCT regardless.
        //
        // Emptied for EVERY supertype, including one that contributed nothing to the removal: `A & B - { f }`
        // with `f` from A loses IS-A with B as well, though every field B declares survives untouched. That is
        // §4.3's own precision ("subtraction revokes IS-A for every parent while keeping lineage"), for the
        // reason §5.9 gives: the clause is head-level, so its effect must be readable without scanning the
        // parents' field sets. An author wanting partial IS-A subtracts first and composes second.
        List<String> contract = construction.removal().isPresent() ? List.of() : transitiveSupertypes;
        return new TypeDefinition(Optional.empty(), kind, parameters, contract, List.of(),
                Optional.empty(), body);
    }

    /**
     * Every field name this declaration's own body mentions, whether it introduces the field or tightens an
     * inherited one. Both are what §5.9 rule 4 forbids a removal from naming, so one set answers both halves.
     */
    private static Set<String> bodyNames(ConstructionDef construction) {
        Set<String> names = new LinkedHashSet<>();
        if (construction.body().isEmpty()) {
            return names;
        }
        for (RecordEntry entry : construction.body().get().entries()) {
            switch (entry) {
                case FieldDef fieldDef -> names.add(fieldDef.name());
                case GroupDef groupDef -> groupDef.members().forEach(member -> names.add(member.name()));
            }
        }
        return names;
    }

    /**
     * §5.9's removal clause, applied last: supertypes merged, then the body, then this (rule 1). Removal reads
     * the <em>merged</em> field set with no regard for which supertype contributed a field (rule 3) -- IS-A is
     * already broken, so there is no contract left to violate.
     *
     * <p>Two things are rejected. A name that is nowhere in the merged set (rule 2, symmetric with
     * refinement's existing-fields-only rule), and a name this declaration's own body mentions (rule 4) --
     * adding a field and then removing it, or tightening a field and then removing it, says two incompatible
     * things in one declaration, and the author meant one of them. Rule 4 is checked first: a body-introduced
     * field <em>is</em> in the merged set, so the weaker "no such field" answer would be the wrong diagnosis.
     *
     * <p>Groups (§5.11): a removed member leaves its group's {@code members}, and a group left with one member
     * is dissolved -- the survivor becomes an ordinary field taking the group's own state, since a group's
     * members are flattened as {@code OPTIONAL} whatever the group says. Removing every member of a group
     * drops the group with them -- §5.11 runs the ladder to zero and states the two-member minimum as an
     * invariant of resolved output.
     */
    private static void applyRemovals(String declarationName, RemovalSet removal, Set<String> bodyDeclared,
                                       List<RecordField> fields, List<FieldGroup> groups) {
        Set<String> removed = new LinkedHashSet<>();
        for (String fieldName : removal.fieldNames()) {
            if (bodyDeclared.contains(fieldName)) {
                throw new TsonSchemaValidationException("'" + declarationName + "': removal names '" + fieldName
                        + "', which this declaration's own body also declares -- a declaration cannot both state "
                        + "a field and remove it (§5.9 rule 4)");
            }
            if (fields.stream().noneMatch(field -> field.name().equals(fieldName))) {
                throw new TsonSchemaValidationException("'" + declarationName + "': removal names '" + fieldName
                        + "', which is not a field of the composed type -- only an inherited field can be "
                        + "removed (§5.9 rule 2)");
            }
            removed.add(fieldName);
        }

        List<FieldGroup> surviving = new ArrayList<>();
        for (FieldGroup group : groups) {
            List<String> members = group.members().stream().filter(member -> !removed.contains(member)).toList();
            if (members.size() == group.members().size()) {
                surviving.add(group);
            } else if (members.size() > 1) {
                surviving.add(new FieldGroup(members, group.state()));
            } else if (members.size() == 1) {
                dissolveInto(fields, members.get(0), group.state());
            }
        }
        groups.clear();
        groups.addAll(surviving);

        fields.removeIf(field -> removed.contains(field.name()));
    }

    /** §5.11: the last member of a dissolved group becomes a plain field carrying the group's own state. */
    private static void dissolveInto(List<RecordField> fields, String member, ElementState groupState) {
        FieldState state = groupState == ElementState.OPTIONAL ? FieldState.OPTIONAL : FieldState.REQUIRED;
        for (int i = 0; i < fields.size(); i++) {
            RecordField field = fields.get(i);
            if (field.name().equals(member)) {
                fields.set(i, field.withState(state));
                return;
            }
        }
    }

    private static void addIfAbsent(List<String> list, Set<String> seen, String name) {
        if (seen.add(name)) {
            list.add(name);
        }
    }

    /**
     * §4.1: a type's kind is settled by which of the kernel's four fixed base-kind names --
     * {@code atom}/{@code product}/{@code sum}/{@code data}, {@code top} never counts -- appear in its
     * transitive supertype chain. This checks those exact literal names, not each ancestor's own
     * resolved {@code kind} field: {@code atom} the entry is itself {@code kind: PRODUCT} (its own
     * chain is just {@code [top]}), so "inherit the nearest ancestor's kind" would give the wrong
     * answer even for {@code atom}'s own resolution.
     */
    private static TypeKind determineKind(String name, List<String> transitiveSupertypes) {
        List<String> baseKindsFound = new ArrayList<>();
        for (String supertype : transitiveSupertypes) {
            if (supertype.equals("atom") || supertype.equals("product") || supertype.equals("sum")
                    || supertype.equals("data")) {
                baseKindsFound.add(supertype);
            }
        }
        if (baseKindsFound.isEmpty()) {
            return TypeKind.PRODUCT;
        }
        if (baseKindsFound.size() > 1) {
            // §4.1's base kinds are disjoint categories, not facets a type can hold several of, so a chain
            // reaching two of them describes nothing. A verdict, not a gap: no improvement to this library
            // makes a type both an atom and a product.
            throw new TsonSchemaValidationException("'" + name + "' reaches " + baseKindsFound.size()
                    + " base kinds through its supertypes (" + String.join(", ", baseKindsFound) + ") -- §4.1 "
                    + "gives a type exactly one, so nothing can be both. Compose or refine from sources that "
                    + "agree on their base kind");
        }
        return switch (baseKindsFound.get(0)) {
            case "atom" -> TypeKind.ATOM;
            case "product" -> TypeKind.PRODUCT;
            case "sum" -> TypeKind.SUM;
            case "data" -> TypeKind.DATA;
            default -> throw new IllegalStateException(baseKindsFound.get(0));
        };
    }

    // ── Refinement (§5.7): T ^ { ... } ─────────────────────────────────────

    /**
     * {@code source ^ { ... }} (optionally {@code ~}-marked and/or parameterized, e.g. {@code
     * set => <T> ~array<T> ^ { state: = REQUIRED ... }}): copies the *entire* inherited field set
     * and any groups from the (already-resolved) source's own {@link RecordBody} -- unlike
     * composition, refinement never adds fields, so every body entry MUST tighten one of them
     * ({@link #resolveTighteningField}, the same machinery composition-body tightening uses); a
     * body field naming nothing inherited is a resolver error (§5.7: "adding fields is a resolver
     * error"), as is a group naming no inherited group. A group that <em>does</em> name one restates it
     * (§5.11, {@link #restatesInheritedGroup}). {@code source}
     * itself -- a bare name or, as here, a generic application ({@code array<T>}) -- is recorded
     * verbatim as the result's {@code source} (§8.1's "a `^` refinement records the source name");
     * unlike composition (which never sets {@code source}), a refinement always does. {@code
     * supertypes} accumulates by the same induction composition uses: {@code [sourceName] +
     * source.supertypes()}, deduplicated -- {@code set}'s own {@code [array, product, top]}. The
     * body's own {@code record.supertypes} stays empty: that field records only direct {@code &}
     * compositions as written (§8.1), and a refinement has no {@code &} list. {@code kind} is
     * determined the same way composition determines it (the transitive chain's literal
     * atom/product/sum name, not the source's own resolved kind).
     */
    private TypeDefinition resolveRefinement(String name, RefinedDef refined,
                                              List<String> parameters) {
        if (refined.target() instanceof GenericRef generic && namesOwnParameter(generic, parameters)) {
            // §5.7 against an open source: the same absorption composition does, and the same two omissions.
            // The source names no entry, so it is neither `source` nor a supertype -- but its own ancestors
            // are types and its whole field set arrives, which is what `^` re-emits and then tightens.
            OpenOperand operand = openOperand(name, generic, parameters, "refinement source");
            return refineOnto(name, refined, parameters, Optional.empty(),
                    new ArrayList<>(operand.ancestors()), operand.body());
        }
        io.ltr8.tson.schema.meta.TypeRef sourceRef = resolveRefinementSource(name, refined.target(), parameters);
        String sourceName = sourceRef.name();
        TypeDefinition sourceDef = namespaceDefinitions.getTypeDefinition(sourceName);
        if (sourceDef == null) {
            // As with a supertype: a refinement reads the source's own field set, so this resolves now.
            throw new TsonSchemaValidationException("'" + name + "': refinement source '" + sourceName
                    + "' names no type this schema declares or imports");
        }
        if (!(sourceDef.body() instanceof RecordBody sourceBody)) {
            // §5.7's "Refinement requires a vocabulary body": the source of ^ MUST be a definition whose body
            // is a !record, and one whose body is a binding record -- a top-level constructor application, a
            // template instantiation, or an alias for either -- is *finished*, its bindings set. The author's
            // error, not a gap: there is no vocabulary here to tighten.
            throw new TsonSchemaValidationException("'" + name + "': refinement source '" + sourceName
                    + "' has no vocabulary to tighten -- its body is a binding record, so it is finished and "
                    + "'^' on it is a resolver error (§5.7). Refine the head it derives from, or, for an atom "
                    + "instance, use atom refinement ('!" + sourceName + " ^ { ... }', §5.5)");
        }

        List<String> transitiveSupertypes = new ArrayList<>();
        Set<String> seenTransitive = new HashSet<>();
        addIfAbsent(transitiveSupertypes, seenTransitive, sourceName);
        for (String ancestor : sourceDef.supertypes()) {
            addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
        }
        return refineOnto(name, refined, parameters, Optional.of(sourceRef), transitiveSupertypes,
                sourceBody);
    }

    /**
     * §5.7's tightening, over a field set already obtained. The two callers differ only in where that field
     * set came from and in what the source can be named: a closed source is an entry, so it is the refinement's
     * {@code source} and heads its supertype chain; an open one names no entry, so it is neither and only its
     * own ancestors survive. Everything after that -- restating groups, tightening fields, the presence check,
     * the kind -- is one rule and lives here once.
     */
    private TypeDefinition refineOnto(String name, RefinedDef refined,
            List<String> parameters, Optional<io.ltr8.tson.schema.meta.TypeRef> source,
            List<String> transitiveSupertypes, RecordBody sourceBody) {
        List<RecordField> fields = new ArrayList<>(sourceBody.fields());
        List<FieldGroup> groups = new ArrayList<>(sourceBody.groups());
        Map<String, Integer> inheritedFieldIndex = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            inheritedFieldIndex.put(fields.get(i).name(), i);
        }

        for (RecordEntry entry : refined.body().entries()) {
            if (entry instanceof GroupDef groupDef) {
                if (!restatesInheritedGroup(name, groupDef, fields, groups, inheritedFieldIndex)) {
                    throw new TsonSchemaValidationException("'" + name + "': the group ("
                            + String.join(" | ", memberNames(groupDef)) + ") names no inherited group -- a "
                            + "refinement copies its source's whole field set and admits no new fields or "
                            + "groups; composition (`&`) is what adds one (§5.7, §5.11)");
                }
                continue;
            }
            FieldDef fieldDef = (FieldDef) entry;
            Integer index = inheritedFieldIndex.get(fieldDef.name());
            if (index == null) {
                throw new TsonSchemaValidationException("'" + name + "': refinement body field '" + fieldDef.name()
                        + "' names no inherited field -- a refinement copies its source's whole field set and "
                        + "admits no new fields; composition (`&`) is what adds one (§5.7)");
            }
            fields.set(index, resolveTighteningField(name, fieldDef, fields.get(index), parameters));
        }
        checkGroupPresence(name, fields, groups);

        TypeKind kind = determineKind(name, transitiveSupertypes);
        RecordBody body = new RecordBody(List.of(), fields, groups);
        return new TypeDefinition(source, kind, parameters, transitiveSupertypes,
                List.of(), Optional.empty(), body);
    }

    /**
     * A refinement's source ({@code target}) is always a {@link SimpleRef} or a {@link
     * GenericRef} by grammar (see {@code RefinedDef}'s own Javadoc) -- a bare name resolves to a
     * bare {@code type_ref}; a generic application (e.g. {@code box<T>}, {@code T} shadowing the
     * refining declaration's own parameter) resolves each argument the way every other application does
     * ({@link #typeArgument}).
     */
    private io.ltr8.tson.schema.meta.TypeRef resolveRefinementSource(String name, TypeRef target,
            List<String> typeParams) {
        if (target instanceof SimpleRef simple) {
            return io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
        }
        if (target instanceof GenericRef generic) {
            // Closed, not carried, for the reason composition closes its supertypes: §5.7 re-emits the
            // source's whole field set, and this phase needs that field set to flatten. Carrying the
            // application instead copied the template's body with its parameters unbound, and the author was
            // told about an unresolved reference to a parameter they never wrote.
            return io.ltr8.tson.schema.meta.TypeRef.of(closedApplication(name, generic, typeParams, "refinement source"));
        }
        // Not a gap and not the author's error: the two shapes above are the two §12.1's `atom-refinement`
        // can produce, so a third means this resolver was handed a tree the grammar cannot build.
        throw new IllegalStateException(
                "'" + name + "': a refinement source is always a simple or generic type-ref by grammar, got " + target);
    }

    /**
     * One source's fields and groups copied into the record being built. Shared by the two composition paths
     * -- a closed supertype's own {@code RecordBody} and an open operand's substituted one -- so an operand
     * that contributes no name still contributes its fields on exactly the terms one that does would.
     */
    private void absorb(String name, RecordBody source, List<RecordField> fields, List<FieldGroup> groups,
            Set<String> seenFieldNames, Map<String, Integer> inheritedFieldIndex) {
        for (RecordField field : source.fields()) {
            requireFieldNameNotSeen(name, field.name(), seenFieldNames, FieldOrigin.SUPERTYPE);
            seenFieldNames.add(field.name());
            inheritedFieldIndex.put(field.name(), fields.size());
            fields.add(field);
        }
        groups.addAll(source.groups());
    }

    /**
     * Whether an application is applied to a parameter of the declaration that writes it, and so still open.
     * <b>Through nesting</b>: {@code box<inner<T>>} is as open as {@code box<T>} is, and reading only the top
     * level sent it down the closing path, where materialisation reported the author's own parameter as an
     * unresolved reference -- a wrong verdict on a schema that is not wrong.
     */
    private static boolean namesOwnParameter(GenericRef application, List<String> typeParams) {
        return application.args().stream().anyMatch(arg -> arg instanceof TypeArg.Ref(TypeRef ref)
                && (ref instanceof SimpleRef simple && typeParams.contains(simple.name())
                        || ref instanceof GenericRef nested && namesOwnParameter(nested, typeParams)));
    }

    /**
     * What an operand still open contributes to the declaration absorbing it: a field set, and the operand's
     * own supertypes. Not the operand itself -- {@code box} is a template, and §5.10 makes a template no type,
     * so nothing can be IS-A one. Its <em>ancestors</em> are types, and the fields arriving here came with
     * them, so a declaration composing {@code box&lt;T&gt;} stands where {@code box}'s own {@code base} is
     * expected.
     *
     * <p><b>Absorbing needs no closure, which is the whole of why this works.</b> The operand's body is held,
     * so its field set is known while the application is open: substituting its parameters with the arguments
     * <em>as written</em> -- which here are the absorbing declaration's own parameters -- is the same token
     * walk {@link TemplateMaterialiser#substitute} performs when the arguments are concrete, and it yields a
     * held record still carrying them. Read back through the {@code record} constructor, that is an ordinary
     * field set whose types mention a parameter, which is exactly what a template's fields are anyway.
     *
     * <p><b>Inner applications are deliberately left unclosed.</b> {@code closeApplications} is
     * materialisation's step, not this one: an operand body holding {@code inner&lt;T&gt;} cannot close while
     * {@code T} is open, and one holding a concrete {@code pair&lt;text, text&gt;} must close at the same
     * moment every other application in the absorbing declaration's body does. Both are the absorbing
     * declaration's own materialisation, one pass later.
     *
     * <p><b>What this cannot give back is one IS-A edge</b>, and it is structural rather than a choice
     * deferred: the application is flattened away here, so when the absorbing declaration is closed nothing
     * remains that says "close {@code box&lt;text&gt;} too, and index against the entry that mints". So
     * {@code vip&lt;text&gt;} stands where {@code customer} and {@code base} are expected and not where
     * {@code box&lt;text&gt;} is, though the hand-written {@code customer & box&lt;text&gt;} does. Accepted:
     * {@code box&lt;T&gt;} was never a type in that declaration, so it claimed IS-A with no instantiation of
     * it in particular.
     */
    private OpenOperand openOperand(String name, GenericRef application, List<String> typeParams, String position) {
        String head = application.name();
        TypeDefinition template = namespaceDefinitions.getTypeDefinition(head);
        if (template == null) {
            throw new TsonSchemaValidationException("'" + name + "': " + position + " '" + head
                    + "' names no type this schema declares or imports");
        }
        if (!(template.body() instanceof HeldBody held)) {
            // Applied to this declaration's own parameter, so the author wrote arguments; the head takes none.
            throw new TsonSchemaValidationException("'" + name + "': " + position + " '" + head
                    + "' declares no type parameters, so it cannot be applied to '"
                    + String.join(", ", typeParams) + "' (§5.10)");
        }
        if (template.parameters().size() != application.args().size()) {
            throw new TsonSchemaValidationException("'" + name + "': " + position + " '" + head + "' declares "
                    + template.parameters().size() + " type parameter(s) and is applied to "
                    + application.args().size() + " (§5.10)");
        }
        Map<String, TypeArgument> bindings = new LinkedHashMap<>();
        for (int i = 0; i < template.parameters().size(); i++) {
            // An argument that is itself an application needs no special case: substitution writes a bound
            // reference in `type_ref`'s record form when it carries arguments, so `box<inner<T>>` keeps
            // `inner<T>` whole and the absorbing declaration's own materialisation closes it.
            bindings.put(template.parameters().get(i), typeArgument(application.args().get(i)));
        }
        DataValue body = held.application();
        CoreValue substituted = WireForm.substitute(body.coreValue(), head,
                template.parameters(), bindings);
        Top absorbed = bindAtomInstance(name, new DataValue(body.annotations(), body.typeRef(), substituted));
        if (!(absorbed instanceof RecordBody record)) {
            // An open instance rather than a record template -- `<T> [T]` has elements, not fields, so there
            // is nothing for `&` or `^` to take. The same verdict its closed spelling gets, one phase earlier.
            throw new TsonSchemaValidationException("'" + name + "': " + position + " '" + head
                    + "<...>' has no fields to contribute -- it is a binding record, not a vocabulary, so "
                    + "there is nothing to compose with (§5.8, and §5.7's vocabulary-body rule read across)");
        }
        return new OpenOperand(template.supertypes(), record);
    }

    /** What an open operand hands its absorber: the ancestors it can still be indexed under, and its fields. */
    private record OpenOperand(List<String> ancestors, RecordBody body) {
    }

    /**
     * A fully-bound application at one of the two field-absorbing positions, closed to the entry it denotes.
     * Closing is what gives it both a name to index under and a field set to absorb.
     *
     * <p>Only reached once {@link #namesOwnParameter} has said the application is closed: one still open
     * denotes no entry and goes to {@link #openOperand} instead, which absorbs its fields without closing
     * anything.
     */
    private String closedApplication(String name, GenericRef application, List<String> typeParams, String position) {
        if (applicationCloser == null) {
            throw new UnsupportedOperationException("'" + name + "': closing the " + position + " '"
                    + application.name() + "<...>' needs a whole-schema materialiser, and this resolver was "
                    + "built without one");
        }
        io.ltr8.tson.schema.meta.TypeRef resolved;
        try {
            resolved = new io.ltr8.tson.schema.meta.TypeRef(application.name(), typeArguments(name, application));
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException("'" + name + "': " + e.getMessage());
        }
        return applicationCloser.closeApplication(resolved);
    }

    private List<TypeArgument> typeArguments(String name, GenericRef application) {
        List<TypeArgument> args = new ArrayList<>();
        for (TypeArg arg : application.args()) {
            args.add(typeArgument(arg));
        }
        return args;
    }

    // ── Record bodies, fields, and field groups (§5.2, §5.11) ─────────────

    private RecordBody resolveRecordBody(List<RecordEntry> entries, List<String> parameters) {
        List<RecordField> fields = new ArrayList<>();
        List<FieldGroup> groups = new ArrayList<>();
        Set<String> seenFieldNames = new HashSet<>();
        for (RecordEntry entry : entries) {
            resolveEntry(null, entry, fields, groups, seenFieldNames, Map.of(), parameters);
        }
        return new RecordBody(List.of(), fields, groups);
    }

    /**
     * {@code declarationName} is only used to word error messages -- {@code null} for a fresh
     * record, where {@code inheritedFieldIndex} is always empty (no supertype means no field could
     * ever be inherited, so every entry is either genuinely new or a genuine duplicate declaration,
     * the latter still unsupported). A {@link FieldDef} whose name is a key of {@code
     * inheritedFieldIndex} is a *tightening* entry (§5.7): it's resolved against, and replaces in
     * place, the already-inherited field at that index, rather than being appended as new.
     */
    private void resolveEntry(String declarationName, RecordEntry entry, List<RecordField> fields,
                               List<FieldGroup> groups, Set<String> seenFieldNames,
                               Map<String, Integer> inheritedFieldIndex, List<String> parameters) {
        switch (entry) {
            case FieldDef fieldDef -> {
                Integer index = inheritedFieldIndex.get(fieldDef.name());
                if (index != null) {
                    fields.set(index, resolveTighteningField(declarationName, fieldDef, fields.get(index), parameters));
                } else {
                    requireFieldNameNotSeen(declarationName, fieldDef.name(), seenFieldNames, FieldOrigin.BODY_FIELD);
                    RecordField field = resolveField(fieldDef, parameters, Optional.empty());
                    seenFieldNames.add(field.name());
                    fields.add(field);
                }
            }
            case GroupDef groupDef -> {
                if (restatesInheritedGroup(declarationName, groupDef, fields, groups, inheritedFieldIndex)) {
                    return;
                }
                List<String> memberNames = new ArrayList<>();
                for (GroupDef.Member member : groupDef.members()) {
                    requireFieldNameNotSeen(declarationName, member.name(), seenFieldNames,
                            FieldOrigin.GROUP_MEMBER);
                    RecordField field = resolveGroupMember(member);
                    seenFieldNames.add(field.name());
                    fields.add(field);
                    memberNames.add(field.name());
                }
                groups.add(new FieldGroup(memberNames, groupDef.optional() ? ElementState.OPTIONAL : ElementState.REQUIRED));
            }
        }
    }

    /**
     * §5.7's refinement/tightening rules, applied to one composition-body field that names an
     * already-inherited field: resolved the same way as any field ({@link #resolveField}), except
     * an elided type-ref (a modifier-only entry, {@code field: = value}) inherits {@code
     * inherited.type()} rather than failing, and the resulting state MUST be a permitted transition
     * from {@code inherited.state()} per §5.7's transition table ({@link
     * #isValidTighteningTransition}) -- e.g. {@code array}'s own {@code access_pattern:
     * product_access_type = INDEX} tightens {@code product}'s {@code REQUIRED} to {@code
     * REQUIRED_FIXED}, an allowed transition. The identity-diagonal rule (a {@code REQUIRED_FIXED}/
     * {@code OPTIONAL_FIXED} restatement MUST NOT change the pinned value) is not checked yet -- no
     * real fixture declaration restates an already-fixed field, so there's nothing to verify it
     * against.
     */
    private RecordField resolveTighteningField(String declarationName, FieldDef fieldDef, RecordField inherited,
                                                List<String> parameters) {
        RecordField tightened = resolveField(fieldDef, parameters, Optional.of(inherited));
        if (!isValidTighteningTransition(inherited.state(), tightened.state())) {
            // §5.7's table is a rule about schemas, not a coverage boundary: "refinement can only restrict,
            // never expand -- FIXED states are terminal, and loosening a required field to optional is a
            // resolver error".
            throw new TsonSchemaValidationException("'" + declarationName + "': tightening '" + fieldDef.name()
                    + "' from " + inherited.state() + " to " + tightened.state() + " is not a permitted state "
                    + "transition -- a refinement can only restrict, never expand (§5.7)");
        }
        return tightened;
    }

    /**
     * §5.7's refinement state-transition table, read row by row (from → permitted targets):
     * {@code REQUIRED} → itself, {@code REQUIRED_DEFAULT}, {@code REQUIRED_FIXED}; {@code OPTIONAL}
     * → any state; {@code REQUIRED_DEFAULT} → itself or {@code REQUIRED_FIXED}; {@code
     * REQUIRED_FIXED} → itself only; {@code OPTIONAL_FIXED} → itself only. Tightening only ever
     * restricts (FIXED states are terminal; OPTIONAL → REQUIRED is the only direction, never back).
     */
    private static boolean isValidTighteningTransition(FieldState from, FieldState to) {
        return switch (from) {
            case REQUIRED -> to == FieldState.REQUIRED || to == FieldState.REQUIRED_DEFAULT || to == FieldState.REQUIRED_FIXED;
            case OPTIONAL -> true;
            case REQUIRED_DEFAULT -> to == FieldState.REQUIRED_DEFAULT || to == FieldState.REQUIRED_FIXED;
            case REQUIRED_FIXED -> to == FieldState.REQUIRED_FIXED;
            case OPTIONAL_FIXED -> to == FieldState.OPTIONAL_FIXED;
        };
    }

    /**
     * Where a colliding name was written, which is the whole content of the diagnostic -- the rule broken is
     * the same one in each case (a field name is unique across a record's plain fields, its groups' members,
     * and everything its supertypes contribute, §5.11), but what the author has to change is not.
     */
    private enum FieldOrigin {
        SUPERTYPE("two supertypes both contribute it -- supertypes MUST contribute disjoint field sets, "
                + "including a diamond where both paths reach the same originating type (§5.8)"),
        BODY_FIELD("this body declares it twice (§5.11: a field name is unique across a record's plain fields "
                + "and all its groups' members)"),
        GROUP_MEMBER("a group member repeats it -- member labels share the enclosing record's field namespace "
                + "(§5.11)");

        private final String explanation;

        FieldOrigin(String explanation) {
            this.explanation = explanation;
        }
    }

    /**
     * Rejects a repeated field name. Every case is the author's error under §5.11's uniqueness rule (§5.8's
     * disjointness rule being the same rule reaching through supertypes), never a coverage gap -- so the
     * diagnostic's job is to say <em>which</em> of the three ways it happened, since that is what decides the
     * fix.
     *
     * <p>Tightening never reaches here: a body field naming an <em>inherited</em> field is routed by {@code
     * inheritedFieldIndex} in {@link #resolveEntry} before this is consulted for that name.
     */
    private static void requireFieldNameNotSeen(String declarationName, String fieldName,
                                                 Set<String> seenFieldNames, FieldOrigin origin) {
        if (seenFieldNames.contains(fieldName)) {
            throw new TsonSchemaValidationException((declarationName == null ? "" : "'" + declarationName + "': ")
                    + "field '" + fieldName + "' is declared more than once -- " + origin.explanation);
        }
    }

    /**
     * A field's default (`{@code ~}`) or fixed (`{@code =}`) modifier value (§5.2, §5.10) is recorded in
     * {@code value} whether it is a literal or a parameter reference -- a parameter and a literal share
     * the one slot, and §8.1's shadowing rule tells them apart: a token is a parameter exactly when its
     * text resolves into the enclosing entry's own {@code parameters}. There is no separate
     * {@code value_param} channel; the kernel no longer declares one, a held body being unread until
     * its parameters are gone.
     *
     * <p>What a parametric modifier still changes is the field's <b>state</b>. A parametric {@code =}
     * (e.g. {@code array}'s {@code element_type: type_ref = T}, {@code T} declared by {@code array =>
     * <T> ...}) leaves the field at its unmarked {@code REQUIRED} -- nothing is actually fixed at
     * declaration, the argument arriving at application (§5.10), so {@code array}'s own {@code
     * element_type} omits {@code state} entirely in output -- and fixation happens at materialisation
     * (§5.7). A parametric {@code ~} still promotes to {@link FieldState#REQUIRED_DEFAULT}, identically
     * to a literal default. A literal modifier promotes {@code state} to {@link
     * FieldState#REQUIRED_DEFAULT} ({@code ~}) or {@link FieldState#REQUIRED_FIXED} ({@code =}) -- or, on
     * an optional field, to {@link FieldState#OPTIONAL_FIXED}. The absent sentinel ({@code = _}) is §5.2's
     * sixth spelling: {@code OPTIONAL_FIXED} carrying no value, forbidding the field's value while keeping
     * it in the contract.
     *
     * <p>{@code inherited}, supplied only from {@link #resolveTighteningField}, is the field this entry
     * tightens. Two things are read off it: its <b>type</b>, when {@code field.type()} is elided ({@code
     * field: = value}, a modifier-only entry, §5.7's "Elided type-refs"), and its <b>state</b>, because §5.2
     * makes {@code = _} valid on a field "declared with {@code ?} <em>or inherited as OPTIONAL</em>" and a
     * modifier-only entry has no {@code ?} of its own to read. A fresh (non-tightening) field always passes
     * {@code Optional.empty()},
     * and an elided type with nothing to inherit from is the <b>author's</b> error, not a gap -- §5.7
     * requires the resolver to reject a modifier-only entry both in a fresh record (no source to elide
     * toward) and in a composition body naming no inherited field, so it raises {@link
     * io.ltr8.tson.schema.TsonSchemaValidationException}.
     *
     * <p><b>A restatement's annotations merge over the inherited ones</b> ({@link #merged}), rather than
     * replacing them: a tightening entry states what it tightens, and §5.7's modifier-only spelling ({@code
     * extra: ?}) has no annotation position at all, so an entry that mentions nothing must not be able to
     * erase what it does not mention.
     */
    private RecordField resolveField(FieldDef field, List<String> parameters, Optional<RecordField> inherited) {
        Annotations own = annotationsOf(field.name(), field.annotations());
        return resolveFieldEntry(field, parameters, inherited)
                .withAnnotations(inherited.map(source -> merged(own, source.annotations())).orElse(own));
    }

    /**
     * A restated field's annotations: the restatement's own first, in source order, then the inherited
     * field's, in source order. One rule, and every part of it is load-bearing.
     *
     * <p><b>Concatenation, not replacement by name.</b> [TSON-DATA] §3.1 makes an annotation name repeatable
     * on one value -- "an annotation name MAY appear any number of times on a single value; all occurrences
     * are preserved in source order" -- so annotations are a list and not a map. Replacing "the inherited
     * {@code @doc}" would need an identity the model does not give them, and has no defined answer where the
     * source carries two. A restated field carrying two {@code @doc}s is the same shape as a field an author
     * wrote two on directly.
     *
     * <p><b>Nearer first, because order is the precedence mechanism already.</b> {@link Annotations#get} and
     * {@link Annotations#value} take the first occurrence, and so does the {@code @bytes_encoding} lookup
     * that resolves the directive nearest-first. Leading with the restatement is what makes the nearer
     * declaration win at every such site without one of them having to know about composition. Inherited-first
     * would hand a field restated under its own {@code @bytes_encoding} the alphabet of the type it tightens.
     *
     * <p>The cost is that a subtype rewriting an inherited {@code @doc} leaves the field carrying both, which
     * is what "annotations are not removable" buys and what §3.1 already accepts anywhere else.
     */
    private static Annotations merged(Annotations restatement, Annotations inherited) {
        if (inherited.isEmpty()) {
            return restatement;
        }
        if (restatement.isEmpty()) {
            return inherited;
        }
        Annotations.Builder merged = new Annotations.Builder();
        restatement.values().forEach(merged::add);
        inherited.values().forEach(merged::add);
        return merged.build();
    }

    /**
     * §6 adds one annotation position to the type-definition grammar: "in {@code field-def}, annotations
     * precede the field name and annotate the field itself, mapping to the {@code record_field} in resolver
     * output". Unlike a declaration, there is no before/after ambiguity here -- a field has one annotation
     * position and it is the field's own.
     */
    private RecordField resolveFieldEntry(FieldDef field, List<String> parameters,
                                           Optional<RecordField> inherited) {
        requireIdentifier(field.name(), "field name");
        io.ltr8.tson.schema.meta.TypeRef type;
        if (field.type().isPresent()) {
            type = resolveTypeRef(field.type().get().typeRef());
        } else if (inherited.isPresent()) {
            type = inherited.get().type();
        } else {
            // §5.7: "a modifier-only entry is always a tightening -- it names no type, so it cannot declare a
            // new field". Reaching here means there was nothing to elide toward: either a fresh record body
            // ("every field MUST have an explicit type-ref, and the resolver MUST reject modifier-only entries
            // there") or a composition body entry naming no inherited field. Both are the author's error, not
            // a gap in this resolver -- the two positions where elision *is* legal resolve above.
            throw new TsonSchemaValidationException("field '" + field.name() + "' states only a modifier and no "
                    + "type-ref, but names no inherited field to take a type from -- a modifier-only entry is "
                    + "always a tightening, so it is only meaningful in a refinement or composition body, "
                    + "against a field the source declares (§5.7)");
        }
        // §5.2's presence axis: the entry's own `?` when it restates a type, otherwise the state it inherits
        // -- `= _` is "valid only when the field is OPTIONAL (declared with `?` OR inherited as OPTIONAL)",
        // and a modifier-only tightening entry (`min: = _`) has no `?` of its own to read.
        boolean optional = field.type().isPresent()
                ? field.type().get().optional()
                : inherited.map(source -> isOptionalState(source.state())).orElse(false);

        // A parameter and a literal share the `value` slot: §8.1's shadowing rule tells them apart, a token
        // being a parameter exactly when its text resolves into the enclosing entry's own `parameters`.
        // What still differs is the *state* -- §5.7 leaves a parametric `= P` at REQUIRED, nothing being
        // fixed until the value is concrete -- and that is what FieldModifiers decides.
        FieldModifiers.Resolved resolved =
                FieldModifiers.of(field.name(), optional, field.modifier(), parameters);
        return new RecordField(field.name(), type, resolved.state(),
                resolved.value().map(DefinitionResolver::toMetaToken), Annotations.empty(),
                positions.of(field));
    }

    /** §5.2's presence axis: the two states under which a conforming value may leave the field out. */
    private static boolean isOptionalState(FieldState state) {
        return state == FieldState.OPTIONAL || state == FieldState.OPTIONAL_FIXED;
    }

    /** {@code schema.meta} has no dependency on {@code tson-compiler}, so it can't reuse {@link TokenValue} directly (see {@link Token}'s own Javadoc) -- this converts field by field instead. */
    private static Token toMetaToken(TokenValue token) {
        Token.Form form = switch (token.form()) {
            case UNQUOTED -> Token.Form.UNQUOTED;
            case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
        };
        return new Token(token.text(), form);
    }

    /**
     * §5.11's presence rule: "Group presence rules are checked against the refined states at schema load: a
     * refinement under which two members of one group are always present (both in a REQUIRED-family state)
     * is a resolver error." A group means <em>at most one</em> member is present (exactly one, if REQUIRED),
     * so two members that must always be there is a contract nothing can satisfy -- every instance of the
     * type would fail validation, for a reason the author never wrote down.
     *
     * <p>Run for a composition body too, not only a refinement. The sentence says "a refinement", but it sits
     * in a paragraph headed "Refinement and composition" whose opening line puts both bodies under §5.7's
     * tightening rules -- and a composition body tightening two members of an inherited group produces the
     * identical unsatisfiable type. Reading it as refinement-only would leave the same defect legal by the
     * other spelling.
     *
     * <p>Only this declaration's own tightenings can trip it, by induction: a group's members are flattened
     * as {@code OPTIONAL} when first declared (§5.11), so a source that passed this check hands on at most
     * one always-present member. Checking the final state rather than the body's edits costs nothing and is
     * what the rule literally asks for.
     *
     * <p>{@code = _} (fixed to absent) is deliberately <em>not</em> always-present: it lands in
     * {@code OPTIONAL_FIXED}, and forbidding one alternative's value is exactly what §5.11 offers it for.
     */
    private static void checkGroupPresence(String declarationName, List<RecordField> fields,
                                            List<FieldGroup> groups) {
        for (FieldGroup group : groups) {
            List<String> alwaysPresent = group.members().stream()
                    .filter(member -> isAlwaysPresent(stateOf(fields, member)))
                    .toList();
            if (alwaysPresent.size() > 1) {
                throw new TsonSchemaValidationException((declarationName == null ? "" : "'" + declarationName + "': ")
                        + "members " + String.join(" and ", alwaysPresent) + " of the group ("
                        + String.join(" | ", group.members()) + ") are both always present, but at most one "
                        + "member of a group may be (§5.11) -- no value could satisfy this type. Leave all but "
                        + "one in an OPTIONAL state, or fix the others to absent ('= _')");
            }
        }
    }

    /**
     * Whether a field in this state is present in every conforming value: REQUIRED must be supplied, and the
     * two REQUIRED-value states supply it themselves. The OPTIONAL pair may be absent -- {@code
     * OPTIONAL_FIXED} pins a value <em>if</em> the field appears, which is not the same as appearing.
     */
    private static boolean isAlwaysPresent(FieldState state) {
        return state == FieldState.REQUIRED || state == FieldState.REQUIRED_DEFAULT
                || state == FieldState.REQUIRED_FIXED;
    }

    private static FieldState stateOf(List<RecordField> fields, String name) {
        for (RecordField field : fields) {
            if (field.name().equals(name)) {
                return field.state();
            }
        }
        throw new IllegalStateException("group member '" + name + "' has no field -- a group's members are "
                + "flattened into the field list as they are resolved, so this cannot happen");
    }

    private static List<String> memberNames(GroupDef groupDef) {
        return groupDef.members().stream().map(GroupDef.Member::name).toList();
    }

    /**
     * §5.11's group restatement, shared by a refinement body and a composition body -- "a body entry may also
     * restate a group: the restated group MUST have the same member labels in the same order (member
     * type-refs restated verbatim), and may tighten state OPTIONAL→REQUIRED; REQUIRED→OPTIONAL is a resolver
     * error, and changing membership is a resolver error."
     *
     * <p>Returns {@code true} having applied the restatement, or {@code false} when this group names nothing
     * inherited and is therefore a genuinely new one -- which a composition body appends and a refinement
     * body rejects, each at its own call site. A fresh record reaches here with an empty {@code
     * inheritedFieldIndex} and so always takes the {@code false} path.
     *
     * <p>Only the <em>group's</em> state changes. Members flatten as {@code OPTIONAL} whatever the group says
     * (§5.11), so a restatement never rewrites the member fields; tightening an individual member is the
     * separate, already-supported gesture of naming it as an ordinary field.
     *
     * <p>Everything checked here is the author's error under a MUST, so each is a {@link
     * TsonSchemaValidationException} -- and each says which of the four rules was broken, since a
     * restatement that got the order wrong and one that changed a member's type need different fixes.
     */
    private boolean restatesInheritedGroup(String declarationName, GroupDef groupDef, List<RecordField> fields,
                                            List<FieldGroup> groups, Map<String, Integer> inheritedFieldIndex) {
        List<String> restated = memberNames(groupDef);
        List<String> inheritedMembers = restated.stream().filter(inheritedFieldIndex::containsKey).toList();
        if (inheritedMembers.isEmpty()) {
            return false;
        }
        String prefix = (declarationName == null ? "" : "'" + declarationName + "': ") + "the restated group ("
                + String.join(" | ", restated) + ") ";
        if (inheritedMembers.size() != restated.size()) {
            throw new TsonSchemaValidationException(prefix + "adds a member the source does not declare -- "
                    + "changing membership is a resolver error (§5.11)");
        }

        int index = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).members().contains(restated.get(0))) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new TsonSchemaValidationException(prefix + "names inherited fields that are not a group -- "
                    + "a group can only restate one the source declares as a group (§5.11)");
        }
        FieldGroup inherited = groups.get(index);
        if (!inherited.members().equals(restated)) {
            throw new TsonSchemaValidationException(prefix + "does not match the inherited group ("
                    + String.join(" | ", inherited.members()) + ") -- a restatement MUST have the same member "
                    + "labels in the same order, and changing membership is a resolver error (§5.11)");
        }
        for (GroupDef.Member member : groupDef.members()) {
            io.ltr8.tson.schema.meta.TypeRef restatedType = resolveTypeRef(member.typeRef());
            io.ltr8.tson.schema.meta.TypeRef inheritedType =
                    fields.get(inheritedFieldIndex.get(member.name())).type();
            if (!restatedType.equals(inheritedType)) {
                throw new TsonSchemaValidationException(prefix + "gives member '" + member.name() + "' the type "
                        + "'" + restatedType.name() + "' where the source declares '" + inheritedType.name()
                        + "' -- member type-refs are restated verbatim (§5.11); narrowing a member's type is "
                        + "done by naming it as an ordinary field");
            }
        }

        ElementState state = groupDef.optional() ? ElementState.OPTIONAL : ElementState.REQUIRED;
        if (inherited.state() == ElementState.REQUIRED && state == ElementState.OPTIONAL) {
            throw new TsonSchemaValidationException(prefix + "loosens a REQUIRED group to OPTIONAL -- a "
                    + "restatement may only tighten OPTIONAL→REQUIRED (§5.11)");
        }
        groups.set(index, new FieldGroup(inherited.members(), state));
        return true;
    }

    private RecordField resolveGroupMember(GroupDef.Member member) {
        return new RecordField(member.name(), resolveTypeRef(member.typeRef()), FieldState.OPTIONAL,
                Optional.empty());
    }

    /**
     * A field/group-member's type-ref, as one of the two shapes that reach resolution: a bare
     * {@link SimpleRef}, or a {@link GenericRef} -- a §5.10 application, or a constructor's own generic
     * vocabulary such as {@code enum}'s {@code members: set<token>}.
     *
     * <p><b>A container sugar form is the third case and is refused</b>, because by this phase every one of
     * them should already be an entry: {@link SchemaDesugarer} lifts each to a declaration and leaves a bare
     * reference behind. One arriving here means either that the phase was skipped, or that the form holds a
     * position the desugar table cannot reduce to a name -- see the branch's own comment.
     */
    private io.ltr8.tson.schema.meta.TypeRef resolveTypeRef(TypeRef ref) {
        if (ref instanceof SimpleRef simple) {
            return io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
        }
        if (ref instanceof GenericRef generic) {
            List<TypeArgument> args = new ArrayList<>();
            for (TypeArg arg : generic.args()) {
                args.add(typeArgument(arg));
            }
            return new io.ltr8.tson.schema.meta.TypeRef(generic.name(), args);
        }
        if (ref instanceof ArrayRef || ref instanceof io.ltr8.tson.compiler.ast.schema.TupleRef
                || ref instanceof io.ltr8.tson.compiler.ast.schema.MapRef
                || ref instanceof io.ltr8.tson.compiler.ast.schema.ChoiceRef) {
            // Every sugar form is lifted to an entry by `SchemaDesugarer` before resolution, closed or open,
            // so one reaching here was not: either a caller resolved raw AST without running that phase, or
            // the form holds a position the desugar table cannot reduce to a name -- an element that is
            // itself an application (`[box<text>]`), whose entry does not exist until materialisation has
            // run, one phase later. Refusing names that. What this replaces built a structural `array<T>`
            // instead -- the representation §11 rejects, which the linker then reports as an arity error
            // against a constructor the author never wrote.
            throw new UnsupportedOperationException("a container sugar form must be lifted to an entry "
                    + "before resolution (§5.3); this one was not, which means either the desugar phase was "
                    + "skipped or a position inside it is an application, which has no entry to name until "
                    + "it is materialised: " + ref);
        }
        throw new IllegalStateException("unhandled type-ref shape: " + ref);
    }
}
