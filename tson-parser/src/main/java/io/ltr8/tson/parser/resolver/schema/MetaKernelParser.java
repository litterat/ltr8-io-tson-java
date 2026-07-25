package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.UriType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses meta-kernel's own source text into its pre-loaded {@link MetaSchema} (Part 2 §1.5): "The
 * {@code !!meta} directive names this file itself -- the one deliberate circularity in the series,
 * closed by pre-loading rather than by resolution: implementations ship the kernel's resolved
 * structure, and this document describes it." Ordinary schema resolution can't bootstrap
 * meta-kernel from nothing -- resolving a constructor-*application* instance ({@code !C value},
 * §5.5, e.g. {@code integer => !integer_type {}}) needs {@code C}'s own vocabulary already known,
 * and for meta-kernel, every {@code C} it uses is defined *within the same file* -- so this class
 * resolves what {@link SchemaResolver} already can (36 of the real fixture's 49 declarations, in
 * one source-order pass), then makes a second pass over the deferred {@code Instance} declarations
 * now that every constructor they reference (including ones declared *later* in the file, e.g.
 * {@code boolean => !enum [true false]} comes before {@code enum}'s own declaration) has a
 * resolved entry to transfer a kind from.
 *
 * <p><b>Produces a {@link MetaSchema}, doesn't extend {@code TsonSchema}.</b> This class is a
 * stateless parser/resolver, the same shape as {@link SchemaParser}/{@link SchemaResolver} --
 * {@link #parse(String)} and {@link #parse()} each return a freshly-built {@link MetaSchema}
 * value rather than being one themselves.
 *
 * <p><b>Constructor application delegates to {@code SchemaResolver.resolve} wherever that's known
 * correct (2026-07-24, Phase B step 6)</b> -- {@code unit}/{@code integer_type}/{@code text_type}
 * instances (and, transitively, anything {@link SchemaResolver#resolveInstance} itself handles)
 * route through the ordinary two-argument {@code resolve(declaration, entries)} overload (the
 * self-hosted case §3.3.1 itself calls out: meta-kernel's own constructors are its own locals, so
 * the type-name namespace alone suffices -- no separate structure namespace needed). This retired
 * this class's own duplicate {@code bindAtom}/{@code TsonMapperReader} plumbing entirely in favor of
 * the single, already-tested implementation in {@code SchemaResolver}.
 *
 * <p><b>Three targets stay hand-picked, not because retiring them wasn't tried, but because
 * generic binding is demonstrably wrong or incomplete for each</b> (see {@link #handPickedBody}):
 * <ul>
 *   <li>{@code uri_type}/{@code regex_type} -- their {@code specification: AtomSpecification}/
 *   {@code constraints: TextType} fields aren't {@code Optional} (correctly: every instance
 *   genuinely always has exactly one RFC citation), but the RFC citation is a *schema-composed*
 *   fixed default (meta-kernel.tn1: {@code uri_type => ~text_type & atom_specification & { spec: =
 *   "https://www.rfc-editor.org/rfc/rfc3986" ... } }), never literally present in any instance's
 *   own {@code {}} body -- generic binding silently leaves the field {@code null} instead of the
 *   real RFC URI.</li>
 *   <li>{@code enum} -- tried routing this through {@code SchemaResolver} too once its own
 *   generalized positional-form support (Phase B step 3, {@code PositionalForm}) existed, and it
 *   broke the real fixture: {@code boolean => !enum [true false]} fails, since {@code "true"}/
 *   {@code "false"} collide with TSON's own boolean-literal shape and get identified as actual
 *   booleans by {@code BaseTypeResolver} before ever reaching {@code EnumBody.members: List<String>}
 *   (confirmed in {@code SchemaResolverTest}'s own
 *   {@code booleanInstanceFailsGenericBindingBecauseItsMembersCollideWithTsonBooleanLiterals}).
 *   {@code boolean} is one of meta-kernel's real 49 declarations this class MUST resolve correctly
 *   -- unlike a resolver-level test, which can document a known gap and move on, the bootstrap
 *   itself can't silently produce a wrong or missing {@code boolean}. {@link #toEnumBody} reads
 *   {@code TokenValue.text()} directly, bypassing base-type identification entirely, which is
 *   correct for every enum member regardless of what it happens to look like.</li>
 * </ul>
 * Every {@code Instance} in the real fixture is registered this way; a declaration whose target
 * isn't registered at all (not even one of these three) is simply left out of the result entirely
 * rather than failing the whole bootstrap.
 *
 * <p><b>{@link #parse()} reads meta-kernel.tn1 packaged as a classpath resource</b> (see this
 * module's {@code build.gradle.kts}, which copies it straight from the repo's own {@code
 * spec/m/meta-kernel.tn1} snapshot into this module's resources at build time -- one file, not a
 * duplicated copy) rather than a filesystem path into the sibling {@code spec/} directory, so the
 * bootstrap works from a built jar, not just a repo checkout.
 */
public final class MetaKernelParser {

    private MetaKernelParser() {
    }

    /** Parses the meta-kernel source bundled with this module (see class Javadoc). */
    public static MetaSchema parse() {
        return parse(readBundledSource());
    }

    public static MetaSchema parse(String source) {
        SchemaDocument document = new SchemaParser(source).parseSchemaDocument();
        Map<String, TypeDefinition> entries = resolveEntries(document);
        return new MetaSchema(document.id(), document.meta(), document.imports(), entries);
    }

    private static String readBundledSource() {
        try (InputStream in = MetaKernelParser.class.getResourceAsStream("/meta-kernel.tn1")) {
            if (in == null) {
                throw new IOException("meta-kernel.tn1 not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, TypeDefinition> resolveEntries(SchemaDocument document) {
        SchemaResolver resolver = new SchemaResolver();
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        List<SchemaMap.Declaration> instances = new ArrayList<>();

        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            if (declaration.typeDef() instanceof Instance) {
                // Deferred to the second pass: an Instance's own kind is transferred from its
                // target, which (e.g. "enum", declared long after "boolean" uses it) may not be
                // resolved yet in source order.
                instances.add(declaration);
                continue;
            }
            entries.put(declaration.name(), resolver.resolve(declaration, entries));
        }

        for (SchemaMap.Declaration declaration : instances) {
            Instance instance = (Instance) declaration.typeDef();
            TypeDefinition target = entries.get(instance.target());
            if (target == null) {
                continue;
            }
            entries.put(declaration.name(), resolveInstanceDeclaration(declaration, instance, target, resolver, entries));
        }
        return entries;
    }

    /**
     * {@code uri_type}/{@code regex_type}/{@code enum} get their hand-picked body (see this class's
     * own Javadoc); every other target delegates straight to {@code SchemaResolver.resolve} --
     * {@code declaration}'s own {@code typeDef()} is still the {@link Instance}, so this reaches
     * exactly {@link SchemaResolver#resolveInstance} the same way any other caller of {@code
     * SchemaResolver} would.
     */
    private static TypeDefinition resolveInstanceDeclaration(SchemaMap.Declaration declaration, Instance instance,
                                                               TypeDefinition target, SchemaResolver resolver,
                                                               Map<String, TypeDefinition> entries) {
        Optional<Top> handPicked = handPickedBody(instance);
        if (handPicked.isPresent()) {
            // §5.5: constructor application transfers only the target's kind; no supertypes, no
            // parameters -- this is construction, not composition or refinement. (SchemaResolver's
            // own resolveInstance builds this identically -- duplicated here only for the three
            // targets that never reach it.)
            return new TypeDefinition(Optional.of(TypeRef.of(instance.target())), target.kind(), List.of(),
                    false, List.of(), List.of(), Optional.empty(), handPicked.get());
        }
        return resolver.resolve(declaration, entries);
    }

    private static Optional<Top> handPickedBody(Instance instance) {
        return switch (instance.target()) {
            case "uri_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(UriType.UNCONSTRAINED);
            }
            case "regex_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(RegexType.UNCONSTRAINED);
            }
            case "enum" -> Optional.of(toEnumBody(instance.value()));
            default -> Optional.empty();
        };
    }

    /**
     * {@code uri_type}/{@code regex_type} are only ever instantiated as a bare {@code {}} in the
     * real fixture -- checked rather than assumed, since their RFC-citation constraint is supplied
     * as a hand-picked constant (see this class's own Javadoc), not parsed from the instance body.
     */
    private static void requireEmptyBody(Instance instance) {
        if (!(instance.value().coreValue() instanceof EmptyBrace)) {
            throw new IllegalStateException(
                    "expected {} for !" + instance.target() + ", found " + instance.value().coreValue());
        }
    }

    /**
     * {@code !enum [true false]}'s value is a bare array (§5.6's positional form for a
     * single-field constructor), not {@code { members: [...] } }.
     *
     * <p>Package-private, not {@code private} -- {@link CoreTn1Parser} reuses this for core.tn1's
     * own local {@code boolean} redeclaration, which hits the identical generic-binding gap this
     * class's own Javadoc already documents for meta-kernel's {@code boolean} (see {@code
     * SPEC-FEEDBACK.md}).
     */
    static EnumBody toEnumBody(DataValue value) {
        if (!(value.coreValue() instanceof ArrayValue array)) {
            throw new IllegalStateException("expected an array for !enum, found " + value.coreValue());
        }
        List<String> members = new ArrayList<>();
        for (ScopedValue element : array.elements()) {
            if (!(element.value().coreValue() instanceof TokenValue token)) {
                throw new IllegalStateException("expected a token enum member, found " + element.value().coreValue());
            }
            members.add(token.text());
        }
        return new EnumBody(members);
    }
}
