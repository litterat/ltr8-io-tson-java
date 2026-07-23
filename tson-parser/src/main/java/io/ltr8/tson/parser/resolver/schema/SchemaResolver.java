package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.ArrayContainerDef;
import io.ltr8.tson.parser.ast.schema.ConstructionDef;
import io.ltr8.tson.parser.ast.schema.ContainerDef;
import io.ltr8.tson.parser.ast.schema.ContainerTypeDef;
import io.ltr8.tson.parser.ast.schema.ElementType;
import io.ltr8.tson.parser.ast.schema.FieldDef;
import io.ltr8.tson.parser.ast.schema.GenericRef;
import io.ltr8.tson.parser.ast.schema.GroupDef;
import io.ltr8.tson.parser.ast.schema.InlineArrayRef;
import io.ltr8.tson.parser.ast.schema.RecordDef;
import io.ltr8.tson.parser.ast.schema.RecordEntry;
import io.ltr8.tson.parser.ast.schema.RefinedDef;
import io.ltr8.tson.parser.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.ast.schema.SimpleRef;
import io.ltr8.tson.parser.ast.schema.SizeSpec;
import io.ltr8.tson.parser.ast.schema.StructuralTypeDef;
import io.ltr8.tson.parser.ast.schema.TypeArg;
import io.ltr8.tson.parser.ast.schema.TypeDef;
import io.ltr8.tson.parser.ast.schema.TypeRef;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves declarations from a {@link SchemaMap} (the grammar-layer AST, {@code tson-parser}) into
 * {@link TypeDefinition}s (Part 2 §8's resolved schema-value shape, {@code io.ltr8.tson.schema.meta})
 * -- an incremental, deliberately narrow resolver, not the full two-pass resolver of §3.4.1. It
 * handles eight constructs so far:
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
 *   <li>A field's inline array sugar {@code [T]} (§5.3) resolves in place to the {@code type_ref}
 *   value {@code { name: array  arguments: [ { name: T } ] } } -- the {@code @alias:field_name}-style
 *   annotation §8.3 would add when {@code T} is itself an aliased reference is not produced yet, so
 *   the bare form is used instead (see {@link #resolveTypeRef}). A field's type-ref may also be an
 *   ordinary generic application ({@code enum}'s own {@code members: set<token>}), resolved the same
 *   way a refinement source's arguments are ({@link #resolveSimpleTypeArg(TypeArg)}, a simple
 *   argument only). Declaration-level sized-array sugar ({@code [T; N..]}/{@code [T; ..M]}/{@code
 *   [T; N..M]}/{@code [T; N]}, §5.3) desugars to a {@code REFERENCE}-kind entry targeting {@code
 *   array_min}/{@code array_max}/{@code array_ranged} (§5.10) -- see {@link
 *   #resolveContainerTypeDef}.</li>
 *   <li>A declaration's own fully-bound top-level application of the {@code map} constructor
 *   (§5.6) -- {@code schema => map<type_name, type_definition>}'s own shape -- resolves as a
 *   construction, not a reference: {@code kind: PRODUCT} (map's family), {@code source} the
 *   applied form, {@code body: !map { key_type: ... value_type: ... }}, no supertypes -- see
 *   {@link #resolveGenericConstructorApplication}. Only {@code map} with two simple type
 *   arguments is handled so far; other constructors and nested/value arguments are not.</li>
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
 *   the declaration is a resolver error. {@code source} is recorded verbatim as the result's own
 *   {@code source} (unlike composition, which never sets it); {@code supertypes} accumulates by the
 *   same induction as composition ({@code [sourceName] + source.supertypes()}); the body's own
 *   {@code record.supertypes} stays empty (that field records only direct {@code &} compositions,
 *   §8.1, and a refinement has none). Verified end-to-end against the real fixture's {@code set}
 *   (refining {@code array}, tightening {@code REQUIRED_DEFAULT} fields to {@code REQUIRED_FIXED})
 *   and {@code array_min}/{@code array_ranged} (each routing an inherited OPTIONAL field to
 *   {@code REQUIRED} by its own value parameter -- an {@code OPTIONAL -&gt; REQUIRED} tightening,
 *   §5.7's table). Restating a field group in a refinement body, and a non-record refinement
 *   source, are not resolved yet.</li>
 * </ul>
 *
 * Everything else -- elided field types outside a tightening entry, an {@code Absent} modifier
 * value ({@code = _}) or any modifier on an OPTIONAL field, the identity-diagonal value-invariant
 * for a restated FIXED field, atom refinement ({@code !I ^ { ... }}, a distinct grammar form from
 * {@code RefinedDef}), constructor application / atom instances ({@code !C value}, {@code Instance}
 * -- {@code value => !unit {}}, {@code boolean => !enum [true false]}, and every other atom-family
 * bootstrap instance in the real fixture use this and are not dispatched at all yet in {@link
 * #resolveTypeDef}), restating a field group in a refinement body, subtraction, a generic type-ref
 * with a nested or value (non-simple) argument, and an inter-supertype field collision -- is
 * explicitly out of scope for now and reported via {@link UnsupportedOperationException} rather
 * than silently mis-resolved; each is a later, separate pass.
 *
 * <p><b>No namespace, only an accumulating "resolved so far" map.</b> A composition's supertypes
 * must already be present in the {@code resolved} map passed to {@link #resolve(SchemaMap.Declaration,
 * Map)} -- {@link #resolveAll} builds this map incrementally, in source order, so a supertype must
 * be declared earlier in the same schema map than anything composing with it (true for every
 * built-in base kind: {@code top} before {@code atom}/{@code product}/{@code sum}/{@code
 * reference}, and {@code atom} before {@code integer_type}). Real forward references and
 * cross-schema imports need the full namespace population of §3.3.2/§3.4.1's Pass 1, not
 * implemented yet.
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
 * <p>Note the two {@code TypeRef}s in play: this class imports {@code tson-parser}'s grammar-layer
 * {@link TypeRef} (a source-text reference) for reading the AST, and refers to {@code
 * io.ltr8.tson.schema.meta.TypeRef} (the resolved reference it produces) by its fully-qualified
 * name -- the two share a name (matching the kernel's own single {@code type_ref} vocabulary type)
 * but live in different packages and are different concepts, so only one can be the unqualified
 * import here.
 */
public final class SchemaResolver {

    /** Resolves a single declaration with no supertypes visible -- fine for a fresh record like {@code integer_size}, not for a composition. */
    public TypeDefinition resolve(SchemaMap.Declaration declaration) {
        return resolve(declaration, Map.of());
    }

    /** Resolves a single declaration against {@code resolved}, the entries already resolved so far (for composition's supertype lookups). */
    public TypeDefinition resolve(SchemaMap.Declaration declaration, Map<String, TypeDefinition> resolved) {
        return resolveTypeDef(declaration.name(), declaration.typeDef(), resolved);
    }

    /** Resolves every declaration in {@code schemaMap}, one entry at a time, in source order, each seeing every entry resolved before it. */
    public TsonSchema resolveAll(SchemaMap schemaMap) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : schemaMap.declarations().values()) {
            entries.put(declaration.name(), resolve(declaration, entries));
        }
        return new TsonSchema(entries);
    }

    private TypeDefinition resolveTypeDef(String name, TypeDef typeDef, Map<String, TypeDefinition> resolved) {
        if (typeDef instanceof StructuralTypeDef structural) {
            List<String> parameters = structural.typeParams();
            boolean constructor = structural.constructor();
            if (structural.body() instanceof RecordDef recordDef) {
                RecordBody body = resolveRecordBody(recordDef.entries(), parameters);
                return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, parameters, constructor,
                        List.of(), List.of(), Optional.empty(), body);
            }
            if (structural.body() instanceof ConstructionDef construction) {
                return resolveComposition(name, construction, resolved, constructor, parameters);
            }
            if (structural.body() instanceof RefinedDef refined) {
                return resolveRefinement(name, refined, resolved, constructor, parameters);
            }
        }
        if (typeDef instanceof ReferenceTypeDef referenceTypeDef && referenceTypeDef.typeParams().isEmpty()) {
            if (referenceTypeDef.ref() instanceof SimpleRef simple) {
                return TypeDefinition.reference(simple.name());
            }
            if (referenceTypeDef.ref() instanceof GenericRef generic) {
                return resolveGenericConstructorApplication(name, generic);
            }
        }
        if (typeDef instanceof ContainerTypeDef containerTypeDef && containerTypeDef.typeParams().isEmpty()) {
            return resolveContainerTypeDef(name, containerTypeDef.container());
        }
        throw new UnsupportedOperationException(
                "'" + name + "': only fresh record constructions, composition, simple type references, and "
                        + "declaration-level sized arrays are resolved so far, got " + typeDef.getClass().getSimpleName());
    }

    // ── Top-level constructor application (§5.6) ──────────────────────────

    /**
     * A fully-bound top-level application of the {@code map} constructor -- {@code schema =>
     * map<type_name, type_definition>}'s own shape -- resolves as a construction, not a reference
     * (§5.6: "a declaration whose body is a fully-bound application of a constructor... resolves as
     * a construction"): {@code kind} from the constructor's family ({@code map} composes with
     * {@code product}, so {@code PRODUCT}), the applied form recorded as {@code source}, the binding
     * record ({@code !map { key_type: ... value_type: ... }}) as {@code body}, and no supertypes
     * (construction transfers kind only, §5.5) -- unlike a non-constructor *template* application
     * (e.g. {@code array_min<T, N>}), which resolves to a {@code REFERENCE} instead (see {@link
     * #resolveContainerTypeDef}). Only {@code map} with exactly two simple (non-generic) type
     * arguments is resolved so far; other constructors (record/array/set/tuple/enum/choice) and
     * nested/value arguments are not attempted yet. The {@code @alias:type_name}-style annotation
     * §8.3 would add for an aliased argument (here, {@code type_name} itself aliasing {@code token})
     * is deliberately not produced, same deferral as {@link #resolveTypeRef}.
     */
    private TypeDefinition resolveGenericConstructorApplication(String name, GenericRef generic) {
        if (!generic.name().equals("map") || generic.args().size() != 2) {
            throw new UnsupportedOperationException("'" + name + "': only a fully-bound 'map<K, V>' "
                    + "application is resolved so far, got " + generic);
        }
        io.ltr8.tson.schema.meta.TypeRef keyType = resolveSimpleTypeArg(name, generic.args().get(0));
        io.ltr8.tson.schema.meta.TypeRef valueType = resolveSimpleTypeArg(name, generic.args().get(1));

        io.ltr8.tson.schema.meta.TypeRef source = new io.ltr8.tson.schema.meta.TypeRef("map",
                List.of(new TypeArgument.Ref(keyType), new TypeArgument.Ref(valueType)));

        return new TypeDefinition(Optional.of(source), TypeKind.PRODUCT, List.of(), false, List.of(),
                List.of(), Optional.empty(), MapBody.of(keyType, valueType));
    }

    private static io.ltr8.tson.schema.meta.TypeRef resolveSimpleTypeArg(String name, TypeArg arg) {
        try {
            return resolveSimpleTypeArg(arg);
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException("'" + name + "': " + e.getMessage());
        }
    }

    private static io.ltr8.tson.schema.meta.TypeRef resolveSimpleTypeArg(TypeArg arg) {
        if (arg instanceof TypeArg.Ref(SimpleRef simple)) {
            return io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
        }
        throw new UnsupportedOperationException("only simple (non-generic) type arguments are resolved so far, got " + arg);
    }

    // ── Declaration-level array size sugar (§5.3, §5.10) ──────────────────

    /**
     * {@code [T; N..]}/{@code [T; ..M]}/{@code [T; N..M]}/{@code [T; N]} desugar to the kernel's
     * size-refinement templates -- {@code array_min<T, N>}/{@code array_max<T, M>}/{@code
     * array_ranged<T, N, M>} (the bare-{@code N} form is {@code array_ranged<T, N, N>}, "two
     * spellings of the same application", §5.3). All three are non-constructor templates, so a
     * fully-bound application resolves to a {@code REFERENCE}-kind entry (§5.10) -- see {@link
     * TypeDefinition#reference(io.ltr8.tson.schema.meta.TypeRef)}'s own Javadoc for why {@code
     * body.target} reuses the application itself rather than a materialised instantiation entry's
     * name. A size-less declaration-level array ({@code id_list => [text]}) is a top-level
     * *constructor* application instead (§5.6) -- a different, not-yet-resolved case -- so it's
     * rejected explicitly here rather than mishandled as a reference.
     */
    private TypeDefinition resolveContainerTypeDef(String name, ContainerDef container) {
        if (!(container instanceof ArrayContainerDef arrayContainer)) {
            throw new UnsupportedOperationException(
                    "'" + name + "': only declaration-level array forms are resolved so far, got " + container.getClass().getSimpleName());
        }
        if (arrayContainer.size().isEmpty()) {
            throw new UnsupportedOperationException("'" + name + "': a size-less declaration-level array "
                    + "is a top-level constructor application (§5.6), not resolved yet");
        }
        ElementType elementType = arrayContainer.elementType();
        if (elementType.optional()) {
            throw new UnsupportedOperationException("'" + name + "': an OPTIONAL array element is not resolved yet");
        }
        if (!(elementType.expr() instanceof ElementType.Expr.Plain plain) || !(plain.typeRef() instanceof SimpleRef elementSimple)) {
            throw new UnsupportedOperationException(
                    "'" + name + "': only a simple (non-nested, non-generic) element type is resolved so far: " + elementType);
        }
        io.ltr8.tson.schema.meta.TypeRef element = io.ltr8.tson.schema.meta.TypeRef.of(elementSimple.name());

        io.ltr8.tson.schema.meta.TypeRef applied = switch (arrayContainer.size().get()) {
            case SizeSpec.Min min -> sizeTemplateApplication("array_min", element, min.lower());
            case SizeSpec.Max max -> sizeTemplateApplication("array_max", element, max.upper());
            case SizeSpec.Ranged ranged -> sizeTemplateApplication("array_ranged", element, ranged.lower(), ranged.upper());
            case SizeSpec.Exact exact -> sizeTemplateApplication("array_ranged", element, exact.bound(), exact.bound());
        };
        return TypeDefinition.reference(applied);
    }

    private static io.ltr8.tson.schema.meta.TypeRef sizeTemplateApplication(
            String templateName, io.ltr8.tson.schema.meta.TypeRef element, String... bounds) {
        List<TypeArgument> arguments = new ArrayList<>();
        arguments.add(new TypeArgument.Ref(element));
        for (String bound : bounds) {
            arguments.add(new TypeArgument.Value(new Token(bound, Token.Form.UNQUOTED)));
        }
        return new io.ltr8.tson.schema.meta.TypeRef(templateName, arguments);
    }

    // ── Composition (§5.8) ────────────────────────────────────────────────

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
                                               Map<String, TypeDefinition> resolved, boolean constructor,
                                               List<String> parameters) {
        if (construction.removal().isPresent()) {
            throw new UnsupportedOperationException("'" + name + "': subtraction is not resolved yet");
        }

        List<String> directSupertypes = new ArrayList<>();
        List<String> transitiveSupertypes = new ArrayList<>();
        Set<String> seenTransitive = new HashSet<>();
        List<RecordField> fields = new ArrayList<>();
        List<FieldGroup> groups = new ArrayList<>();
        Set<String> seenFieldNames = new HashSet<>();
        Map<String, Integer> inheritedFieldIndex = new LinkedHashMap<>();

        for (TypeRef supertypeRef : construction.supertypes()) {
            if (!(supertypeRef instanceof SimpleRef simple)) {
                throw new UnsupportedOperationException(
                        "'" + name + "': only simple supertype references are resolved so far, got " + supertypeRef);
            }
            String supertypeName = simple.name();
            TypeDefinition supertypeDef = resolved.get(supertypeName);
            if (supertypeDef == null) {
                throw new UnsupportedOperationException("'" + name + "': supertype '" + supertypeName
                        + "' is not resolved yet (only supertypes declared earlier in the same schema map are visible so far)");
            }
            if (!(supertypeDef.body() instanceof RecordBody supertypeBody)) {
                throw new UnsupportedOperationException("'" + name + "': supertype '" + supertypeName
                        + "' does not have a record body -- composing with a non-record supertype is not resolved yet");
            }

            directSupertypes.add(supertypeName);
            addIfAbsent(transitiveSupertypes, seenTransitive, supertypeName);
            for (String ancestor : supertypeDef.supertypes()) {
                addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
            }

            for (RecordField field : supertypeBody.fields()) {
                requireFieldNameNotSeen(name, field.name(), seenFieldNames);
                seenFieldNames.add(field.name());
                inheritedFieldIndex.put(field.name(), fields.size());
                fields.add(field);
            }
            groups.addAll(supertypeBody.groups());
        }

        if (construction.body().isPresent()) {
            for (RecordEntry entry : construction.body().get().entries()) {
                resolveEntry(name, entry, fields, groups, seenFieldNames, inheritedFieldIndex, parameters);
            }
        }

        TypeKind kind = determineKind(name, transitiveSupertypes);
        RecordBody body = new RecordBody(directSupertypes, fields, groups);
        return new TypeDefinition(Optional.empty(), kind, parameters, constructor, transitiveSupertypes, List.of(),
                Optional.empty(), body);
    }

    private static void addIfAbsent(List<String> list, Set<String> seen, String name) {
        if (seen.add(name)) {
            list.add(name);
        }
    }

    /**
     * §4.1: a type's kind is settled by which of the kernel's three fixed base-kind names --
     * {@code atom}/{@code product}/{@code sum}, {@code top} never counts -- appear in its
     * transitive supertype chain. This checks those exact literal names, not each ancestor's own
     * resolved {@code kind} field: {@code atom} the entry is itself {@code kind: PRODUCT} (its own
     * chain is just {@code [top]}), so "inherit the nearest ancestor's kind" would give the wrong
     * answer even for {@code atom}'s own resolution.
     */
    private static TypeKind determineKind(String name, List<String> transitiveSupertypes) {
        List<String> baseKindsFound = new ArrayList<>();
        for (String supertype : transitiveSupertypes) {
            if (supertype.equals("atom") || supertype.equals("product") || supertype.equals("sum")) {
                baseKindsFound.add(supertype);
            }
        }
        if (baseKindsFound.isEmpty()) {
            return TypeKind.PRODUCT;
        }
        if (baseKindsFound.size() > 1) {
            throw new UnsupportedOperationException(
                    "'" + name + "': multiple base kinds in transitive supertype chain: " + baseKindsFound);
        }
        return switch (baseKindsFound.get(0)) {
            case "atom" -> TypeKind.ATOM;
            case "product" -> TypeKind.PRODUCT;
            case "sum" -> TypeKind.SUM;
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
     * error"), and restating a field group in a refinement body is not resolved yet. {@code source}
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
    private TypeDefinition resolveRefinement(String name, RefinedDef refined, Map<String, TypeDefinition> resolved,
                                              boolean constructor, List<String> parameters) {
        io.ltr8.tson.schema.meta.TypeRef sourceRef = resolveRefinementSource(name, refined.target());
        String sourceName = sourceRef.name();
        TypeDefinition sourceDef = resolved.get(sourceName);
        if (sourceDef == null) {
            throw new UnsupportedOperationException("'" + name + "': refinement source '" + sourceName
                    + "' is not resolved yet (only a source declared earlier in the same schema map is visible so far)");
        }
        if (!(sourceDef.body() instanceof RecordBody sourceBody)) {
            throw new UnsupportedOperationException("'" + name + "': refinement source '" + sourceName
                    + "' does not have a record body -- refining a non-record source is not resolved yet");
        }

        List<String> transitiveSupertypes = new ArrayList<>();
        Set<String> seenTransitive = new HashSet<>();
        addIfAbsent(transitiveSupertypes, seenTransitive, sourceName);
        for (String ancestor : sourceDef.supertypes()) {
            addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
        }

        List<RecordField> fields = new ArrayList<>(sourceBody.fields());
        List<FieldGroup> groups = new ArrayList<>(sourceBody.groups());
        Map<String, Integer> inheritedFieldIndex = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            inheritedFieldIndex.put(fields.get(i).name(), i);
        }

        for (RecordEntry entry : refined.body().entries()) {
            if (!(entry instanceof FieldDef fieldDef)) {
                throw new UnsupportedOperationException(
                        "'" + name + "': restating a field group in a refinement body is not resolved yet");
            }
            Integer index = inheritedFieldIndex.get(fieldDef.name());
            if (index == null) {
                throw new UnsupportedOperationException("'" + name + "': refinement body field '" + fieldDef.name()
                        + "' names no inherited field -- adding a field in a refinement is a resolver error (§5.7)");
            }
            fields.set(index, resolveTighteningField(name, fieldDef, fields.get(index), parameters));
        }

        TypeKind kind = determineKind(name, transitiveSupertypes);
        RecordBody body = new RecordBody(List.of(), fields, groups);
        return new TypeDefinition(Optional.of(sourceRef), kind, parameters, constructor, transitiveSupertypes,
                List.of(), Optional.empty(), body);
    }

    /**
     * A refinement's source ({@code target}) is always a {@link SimpleRef} or a {@link
     * GenericRef} by grammar (see {@code RefinedDef}'s own Javadoc) -- a bare name resolves to a
     * bare {@code type_ref}; a generic application (e.g. {@code array<T>}, {@code T} shadowing the
     * refining declaration's own parameter) resolves each argument the same way a top-level
     * constructor application does ({@link #resolveSimpleTypeArg}), since only a simple
     * (non-nested, non-value) type argument is supported so far.
     */
    private io.ltr8.tson.schema.meta.TypeRef resolveRefinementSource(String name, TypeRef target) {
        if (target instanceof SimpleRef simple) {
            return io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
        }
        if (target instanceof GenericRef generic) {
            List<TypeArgument> args = new ArrayList<>();
            for (TypeArg arg : generic.args()) {
                args.add(new TypeArgument.Ref(resolveSimpleTypeArg(name, arg)));
            }
            return new io.ltr8.tson.schema.meta.TypeRef(generic.name(), args);
        }
        throw new UnsupportedOperationException(
                "'" + name + "': a refinement source is always a simple or generic type-ref by grammar, got " + target);
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
                    requireFieldNameNotSeen(declarationName, fieldDef.name(), seenFieldNames);
                    RecordField field = resolveField(fieldDef, parameters, Optional.empty());
                    seenFieldNames.add(field.name());
                    fields.add(field);
                }
            }
            case GroupDef groupDef -> {
                List<String> memberNames = new ArrayList<>();
                for (GroupDef.Member member : groupDef.members()) {
                    requireFieldNameNotSeen(declarationName, member.name(), seenFieldNames);
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
        RecordField tightened = resolveField(fieldDef, parameters, Optional.of(inherited.type()));
        if (!isValidTighteningTransition(inherited.state(), tightened.state())) {
            throw new UnsupportedOperationException("'" + declarationName + "': tightening '" + fieldDef.name()
                    + "' from " + inherited.state() + " to " + tightened.state() + " is not a permitted state "
                    + "transition (§5.7)");
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
     * Rejects an inter-supertype field collision (§5.8: "supertypes MUST contribute disjoint field
     * sets") and a genuine duplicate field/group-member name within one body -- neither is
     * tightening (tightening a *body* field against an *inherited* one is handled separately, via
     * {@code inheritedFieldIndex} in {@link #resolveEntry}, before this is ever consulted for that
     * name) and neither is resolved here.
     */
    private static void requireFieldNameNotSeen(String declarationName, String fieldName, Set<String> seenFieldNames) {
        if (seenFieldNames.contains(fieldName)) {
            throw new UnsupportedOperationException("'" + declarationName + "': an inter-supertype field collision, "
                    + "or a duplicate field/group-member name ('" + fieldName + "'), is not resolved yet");
        }
    }

    /**
     * A field's default (`{@code ~}`) or fixed (`{@code =}`) modifier value (§5.2, §5.10) resolves
     * one of two ways: when the modifier's token names one of the *declaration's own* type
     * parameters (e.g. {@code array}'s {@code element_type: type_ref = T}, {@code T} declared by
     * {@code array => <T> ...}), it is a parameter reference, not a literal -- recorded as {@code
     * value_param} rather than {@code value} (§5.10's "labelled form", used uniformly whether the
     * routed field is a scalar or {@code type_ref}-typed). A parametric {@code =} leaves the field's
     * state at its unmarked {@code REQUIRED} (nothing is actually fixed at declaration -- the
     * argument arrives at application, §5.10 -- so {@code array}'s own {@code element_type} omits
     * {@code state} entirely in output); a parametric {@code ~} still promotes to {@link
     * FieldState#REQUIRED_DEFAULT}, identically to a literal default. Any other modifier token is an
     * ordinary literal, recorded as {@code value} with {@code state} promoted to {@link
     * FieldState#REQUIRED_DEFAULT} ({@code ~}) or {@link FieldState#REQUIRED_FIXED} ({@code =}). An
     * {@code Absent} modifier value ({@code = _}, valid only on an OPTIONAL field) and a modifier on
     * an OPTIONAL field at all ({@link FieldState#OPTIONAL_FIXED}) are not resolved yet -- no real
     * fixture declaration needs either so far.
     *
     * <p>{@code inheritedType}, supplied only from {@link #resolveTighteningField}, is used when
     * {@code field.type()} is elided ({@code field: = value}, a modifier-only entry, §5.7's own
     * "Elided type-refs": the field's type is inherited from the source declaration and only the
     * value state changes); a fresh (non-tightening) field always passes {@code Optional.empty()}
     * and an elided type there is still a resolver error (a fresh record has no source to elide
     * toward, per §5.7).
     */
    private RecordField resolveField(FieldDef field, List<String> parameters,
                                      Optional<io.ltr8.tson.schema.meta.TypeRef> inheritedType) {
        io.ltr8.tson.schema.meta.TypeRef type;
        if (field.type().isPresent()) {
            type = resolveTypeRef(field.type().get().typeRef());
        } else if (inheritedType.isPresent()) {
            type = inheritedType.get();
        } else {
            throw new UnsupportedOperationException("an elided field type outside a tightening entry is not resolved yet: " + field);
        }
        boolean optional = field.type().map(FieldDef.FieldType::optional).orElse(false);

        if (field.modifier().isEmpty()) {
            FieldState state = optional ? FieldState.OPTIONAL : FieldState.REQUIRED;
            return new RecordField(field.name(), type, state, Optional.empty(), Optional.empty());
        }
        FieldDef.Modifier modifier = field.modifier().get();
        if (!(modifier.value() instanceof FieldDef.Modifier.Value.Literal literal)) {
            throw new UnsupportedOperationException("an absent field-modifier value ('= _') is not resolved yet: " + field);
        }
        if (optional) {
            throw new UnsupportedOperationException("a default/fixed value on an OPTIONAL field is not resolved yet: " + field);
        }
        boolean isParameterReference = parameters.contains(literal.token().text());
        if (isParameterReference) {
            FieldState state = modifier.kind() == FieldDef.Modifier.Kind.DEFAULT ? FieldState.REQUIRED_DEFAULT : FieldState.REQUIRED;
            return new RecordField(field.name(), type, state, Optional.empty(), Optional.of(literal.token().text()));
        }
        FieldState state = modifier.kind() == FieldDef.Modifier.Kind.DEFAULT
                ? FieldState.REQUIRED_DEFAULT : FieldState.REQUIRED_FIXED;
        return new RecordField(field.name(), type, state, Optional.of(toMetaToken(literal.token())), Optional.empty());
    }

    /** {@code schema.meta} has no dependency on {@code tson-parser}, so it can't reuse {@link TokenValue} directly (see {@link Token}'s own Javadoc) -- this converts field by field instead. */
    private static Token toMetaToken(TokenValue token) {
        Token.Form form = switch (token.form()) {
            case UNQUOTED -> Token.Form.UNQUOTED;
            case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
        };
        return new Token(token.text(), form);
    }

    private RecordField resolveGroupMember(GroupDef.Member member) {
        return new RecordField(member.name(), resolveTypeRef(member.typeRef()), FieldState.OPTIONAL,
                Optional.empty(), Optional.empty());
    }

    /**
     * A field/group-member's type-ref: a bare simple reference, a generic application ({@code
     * enum}'s own {@code members: set<token>}, resolved the same way a refinement source's
     * arguments are, via {@link #resolveSimpleTypeArg(TypeArg)} -- only a simple, non-nested
     * argument is supported so far, same limit as elsewhere), or the inline array sugar {@code [T]}
     * (§5.3), which desugars to the constructor application {@code !array { element_type: T }} --
     * represented in place as a {@code type_ref} value, {@code { name: array  arguments: [ { name:
     * T } ] } }, exactly like any other generic application (§5.3: "An inline constructor
     * application does not materialise a schema entry"). Per the same section this would carry
     * {@code @alias:field_name} when {@code T} is itself an aliased reference (§8.3's reference
     * flattening) -- not implemented yet, so the bare, unaliased form is produced instead.
     */
    private io.ltr8.tson.schema.meta.TypeRef resolveTypeRef(TypeRef ref) {
        if (ref instanceof SimpleRef simple) {
            return io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
        }
        if (ref instanceof GenericRef generic) {
            List<TypeArgument> args = new ArrayList<>();
            for (TypeArg arg : generic.args()) {
                args.add(new TypeArgument.Ref(resolveSimpleTypeArg(arg)));
            }
            return new io.ltr8.tson.schema.meta.TypeRef(generic.name(), args);
        }
        if (ref instanceof InlineArrayRef inlineArray && inlineArray.elementType() instanceof SimpleRef elementSimple) {
            return new io.ltr8.tson.schema.meta.TypeRef("array",
                    List.of(new TypeArgument.Ref(io.ltr8.tson.schema.meta.TypeRef.of(elementSimple.name()))));
        }
        throw new UnsupportedOperationException(
                "only simple (non-generic) type-refs, generic applications of one, and inline arrays of one "
                        + "are resolved so far: " + ref);
    }
}
