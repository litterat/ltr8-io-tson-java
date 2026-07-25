package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

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
 * <p><b>Every {@code Instance} declaration resolves through {@link BootstrapMetaKernelCompiler},
 * not {@code SchemaResolver}/{@code TsonMapperReader} at all</b> (widened 2026-07-25 from an
 * earlier version that routed {@code unit}/{@code integer_type}/{@code text_type} through ordinary
 * generic resolution and hand-picked only {@code uri_type}/{@code regex_type}/{@code enum}).
 * {@code SchemaResolver.resolveInstance}'s own generic path binds via {@code TsonMapperReader},
 * which is identification-first (a token is classified null/boolean/number/string *before* the
 * target field is consulted) -- exactly why {@code boolean => !enum [true false]} needs hand-picked
 * handling at all ({@code "true"}/{@code "false"} misidentify as real booleans before {@code
 * EnumBody.members} ever sees them) and why {@code uri_type}/{@code regex_type}'s own
 * schema-composed RFC-citation default never lands (nested inside {@code specification:
 * AtomSpecification}, past what generic defaulting fills in). Rather than deciding case by case
 * which of meta-kernel's own instances need hand-picking and which can use the generic path,
 * {@link BootstrapMetaKernelCompiler} hand-picks all of them uniformly -- meta-kernel only ever
 * instantiates its own six real constructors in two known shapes (a bare {@code {}} or a bare array
 * of tokens), confirmed directly against the real fixture, so there's no genuine need for a general
 * mechanism here at all. See that class's own Javadoc for the full reasoning, including why even a
 * schema-driven *compiled* reader (the eventual replacement for {@code TsonMapperReader} elsewhere,
 * see {@code SchemaResolver}'s own notes) can't safely bootstrap meta-kernel from its own
 * in-progress state either -- {@code enum}'s own {@code members: set<token>} field is
 * argument-bearing, and only {@code SchemaValidator}'s materialization pass (never run over
 * meta-kernel while meta-kernel is still being produced) makes that safe to compile a reader
 * against.
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
            BootstrapMetaKernelCompiler.compile(instance).ifPresent(body -> entries.put(declaration.name(),
                    new TypeDefinition(Optional.of(TypeRef.of(instance.target())), target.kind(), List.of(),
                            false, List.of(), List.of(), Optional.empty(), body)));
        }
        return entries;
    }

    /**
     * {@code !enum [true false]}'s value is a bare array (§5.6's positional form for a
     * single-field constructor), not {@code { members: [...] } }.
     *
     * <p>Package-private, not {@code private} -- both {@link BootstrapMetaKernelCompiler} (this
     * class's own {@code enum} case) and {@link CoreTn1Parser} (core.tn1's own local {@code boolean}
     * redeclaration, the identical generic-binding gap this class's own Javadoc already documents
     * for meta-kernel's {@code boolean} -- see {@code SPEC-FEEDBACK.md}) reuse this.
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
