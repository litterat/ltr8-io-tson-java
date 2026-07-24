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
import io.ltr8.tson.schema.meta.TypeBody;
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
 * value rather than being one themselves.
 *
 * <p><b>Constructor-application binding is done by hand, not through generic object binding</b> --
 * deliberately, so this class needs nothing beyond {@code tson-parser}/{@code tson-schema} (both
 * already main-scope dependencies of this module) and can live here rather than needing the
 * generic reflective binding {@code io.ltr8.tson.parser.mapper}'s {@code TsonMapperReader} offers
 * (an earlier version of this class used {@code TsonMapper.toObject} and lived in a separate {@code
 * tson-mapper} module for exactly that reason; moved into this module once the generic-binding step
 * turned out to be unnecessary for meta-kernel's own narrow bootstrap set -- that module no longer
 * exists at all today, since {@code TsonMapperReader}/{@code TsonMapperWriter} themselves moved
 * here too, for the *opposite* reason: {@code SchemaResolver}'s generalized constructor-application/
 * atom-refinement resolution genuinely does need generic binding).
 * An {@code Instance}'s {@code value} is already a parsed {@link DataValue} (the schema grammar
 * reuses Part 1's own data-value parsing directly, per {@code SchemaParser}'s own Javadoc), and
 * every registered target's shape is simple enough to check directly against the AST: {@code
 * unit}/{@code integer_type}/{@code text_type}/{@code uri_type}/{@code regex_type} are all always
 * instantiated as a bare {@code {}} in the real fixture (every field each of the latter four has is
 * {@code Optional}, so an empty body is exactly that type's own {@code UNCONSTRAINED} constant) --
 * verified by requiring the value actually be an {@link EmptyBrace}, not just assumed -- and {@code
 * enum}'s value is a bare array (§5.6's positional form for a single-field constructor), read
 * directly into an {@link EnumBody}. Every {@code Instance} in the real fixture is registered this
 * way (§5.5's other constructor-application forms -- a non-empty body -- don't occur in
 * meta-kernel itself, so aren't handled); a declaration whose target isn't registered is simply
 * left out of the result entirely rather than failing the whole bootstrap.
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
            Optional<TypeBody> body = bindInstanceBody(instance);
            if (body.isEmpty()) {
                continue;
            }
            // §5.5: constructor application transfers only the target's kind; no supertypes,
            // no parameters -- this is construction, not composition or refinement.
            entries.put(declaration.name(), new TypeDefinition(
                    Optional.of(TypeRef.of(instance.target())), target.kind(), List.of(), false,
                    List.of(), List.of(), Optional.empty(), body.get()));
        }
        return entries;
    }

    private static Optional<TypeBody> bindInstanceBody(Instance instance) {
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

    /**
     * {@code unit}/{@code integer_type}/{@code text_type}/{@code uri_type}/{@code regex_type} are
     * only ever instantiated as a bare {@code {}} in the real fixture -- checked rather than
     * assumed, since none of these constructors' own vocabulary is actually consulted here.
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
     */
    private static EnumBody toEnumBody(DataValue value) {
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
