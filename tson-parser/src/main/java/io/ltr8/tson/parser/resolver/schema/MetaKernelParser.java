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
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
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
 * value rather than being one themselves. Its own output is a resolved-but-not-yet-registered
 * schema -- a caller registers it (and, separately, compiles it -- a distinct, later stage this
 * class has nothing to do with; see {@code SchemaValidator}/{@code TsonSchemaParser}).
 *
 * <p><b>Every {@code Instance} declaration resolves through {@link #instanceBody}, a closed,
 * hand-written switch -- not {@code SchemaResolver}/{@code TsonMapperReader}, and not any
 * schema-driven compiled reader either</b> (widened 2026-07-25 from an earlier version that routed
 * {@code unit}/{@code integer_type}/{@code text_type} through ordinary generic resolution and
 * hand-picked only {@code uri_type}/{@code regex_type}/{@code enum}; merged from a separate
 * {@code BootstrapMetaKernelCompiler} class the same day -- it had exactly one caller, produced a
 * {@link Top} *value* rather than a compiled reader, and "Compiler" in its name collided with what
 * that word means for a class that actually produces a {@code TsonSchemaParser}-shaped artifact,
 * once that became a real, distinct concept in this codebase). Two things rule out both of the more
 * "general" mechanisms this could otherwise reach for:
 * <ul>
 *   <li>{@code SchemaResolver.resolveInstance}'s own generic path binds via {@code TsonMapperReader},
 *   which is identification-first (a token is classified null/boolean/number/string *before* the
 *   target field is consulted) -- exactly why {@code boolean => !enum [true false]} needs
 *   hand-picked handling at all ({@code "true"}/{@code "false"} misidentify as real booleans before
 *   {@code EnumBody.members} ever sees them) and why {@code uri_type}/{@code regex_type}'s own
 *   schema-composed RFC-citation default never lands (nested inside {@code specification:
 *   AtomSpecification}, past what generic defaulting fills in).</li>
 *   <li>A schema-driven *compiled* reader (the eventual replacement for {@code TsonMapperReader}
 *   elsewhere, see {@code SchemaResolver}'s own notes) can't safely bootstrap meta-kernel from its
 *   own in-progress state either -- {@code enum}'s own {@code members: set<token>} field is
 *   argument-bearing, and only {@code SchemaValidator}'s materialization pass (never run over
 *   meta-kernel while meta-kernel is still being produced) makes that safe to compile a reader
 *   against. Checked directly, not assumed: running materialization over the first-pass-only state
 *   doesn't work either -- {@code integer_size => { bits: ... signed: boolean }} is itself a
 *   first-pass entry whose {@code signed} field already references {@code boolean}, unresolved
 *   until the second pass.</li>
 * </ul>
 * Rather than deciding case by case which of meta-kernel's own instances need hand-picking and
 * which can use one of those, {@link #instanceBody} hand-picks all of them uniformly -- meta-kernel
 * only ever instantiates its own six real constructors in two known shapes (a bare {@code {}} or a
 * bare array of tokens), confirmed directly against the real fixture, so there's no genuine need for
 * a general mechanism here at all: "the bootstrap compiler can do whatever tricks it needs to...
 * that includes not even compiling, just calling {@code new Xxx(...)}."
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
            // §5.5: constructor application transfers only the target's kind; no supertypes, no
            // parameters -- this is construction, not composition or refinement.
            instanceBody(instance).ifPresent(body -> entries.put(declaration.name(),
                    new TypeDefinition(Optional.of(TypeRef.of(instance.target())), target.kind(), List.of(),
                            false, List.of(), List.of(), Optional.empty(), body)));
        }
        return entries;
    }

    /**
     * The direct, hand-written construction for one of meta-kernel's own six real constructor
     * targets (see this class's own Javadoc) -- {@link Optional#empty()} for anything else, left
     * for the caller to decide what that means (today: the declaration is simply left out of the
     * result, rather than failing the whole bootstrap; unexercised against the real fixture, since
     * all six real targets are covered).
     *
     * <p>Package-private, not {@code private} -- {@code MetaKernelParserTest} exercises the
     * unrecognized-target and wrong-shape-body branches directly, since neither is reachable through
     * the real fixture (every real target is one of the six, and every empty-bodied one really is
     * empty).
     */
    static Optional<Top> instanceBody(Instance instance) {
        return switch (instance.target()) {
            case "unit" -> {
                requireEmptyBody(instance);
                yield Optional.of(new Unit());
            }
            case "integer_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(IntegerType.UNCONSTRAINED);
            }
            case "text_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(TextType.UNCONSTRAINED);
            }
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

    /** Every empty-bodied target above is only ever instantiated as a bare {@code {}} in the real fixture -- checked rather than assumed, since each one's own constraint value is a hand-picked constant, not parsed from the instance body. */
    private static void requireEmptyBody(Instance instance) {
        if (!(instance.value().coreValue() instanceof EmptyBrace)) {
            throw new IllegalStateException(
                    "expected {} for !" + instance.target() + ", found " + instance.value().coreValue());
        }
    }

    /**
     * {@code !enum [true false]}'s value is a bare array (§5.6's positional form for a
     * single-field constructor), not {@code { members: [...] } }.
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
