package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.mapper.TsonMapperReader;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.meta.Atom;
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
 * <p><b>Constructor-application binding for fully data-representable targets goes through generic
 * binding (2026-07-24), not hand construction.</b> {@code unit}/{@code integer_type}/{@code
 * text_type} instances bind via a single call, {@code TsonMapperReader.toObject(instance.value(),
 * Atom.class)} -- {@code instance.value()} already carries the constructor's own name as its {@code
 * DataValue.typeRef} (per {@code Instance}'s own reshape, {@code SPEC-FEEDBACK.md} #16), so {@code
 * tson-bind}'s own union-member resolution (matching that name against each {@link Atom} leaf's
 * {@code @Typename}, no hand-rolled name→class table anywhere) finds {@code IntegerType}/{@code
 * TextType}/etc. and binds it generically -- record binding naturally produces the same result as
 * the old hand-picked {@code UNCONSTRAINED} constants for an empty {@code {}} body (every field on
 * these three is {@code Optional}), and, as a genuine capability gain over the old hand-rolled
 * switch, now also handles a non-empty body correctly instead of rejecting it.
 *
 * <p><b>{@code uri_type}/{@code regex_type} deliberately stay hand-picked constants</b> -- tried
 * generic binding for these too and it silently produced the wrong value: their own {@code
 * specification: AtomSpecification}/{@code constraints: TextType} fields aren't {@code Optional}
 * (correctly -- every {@code uri_type}/{@code regex_type} instance genuinely always has exactly one
 * RFC citation, never an absent one), but the RFC citation is a *schema-composed* fixed default
 * (meta-kernel.tn1: {@code uri_type => ~text_type & atom_specification & { spec: =
 * "https://www.rfc-editor.org/rfc/rfc3986" ... } }), never literally present in any instance's own
 * {@code {}} body -- so plain record binding leaves that field {@code null} instead of the real RFC
 * URI (caught by {@code MetaKernelParserTest.textUriRegexResolveToTheirUnconstrainedTypeBodiesWithAtomKind}).
 * Binding what's actually written in the instance body is correct as far as it goes; reconstructing
 * a *composed* default that lives on the constructor's own declaration, not the instance, is a
 * different problem this class doesn't attempt to solve generically -- {@link #requireEmptyBody}
 * keeps the same defensive check the original hand-written path had.
 *
 * <p>{@code enum}'s value is still hand-unwrapped (see {@link #toEnumBody}) -- it's a bare array
 * (§5.6's positional form for a single-field constructor), which {@code TsonMapperReader} can't bind
 * directly yet (no positional-form support, see {@code CLAUDE.md}'s "Mapper" section); that gap is
 * what {@code SchemaResolver}'s own generalized constructor-application resolution (Phase B step 3,
 * not built yet) will close for every constructor at once, at which point this method's {@code enum}
 * case can retire too. Every {@code Instance} in the real fixture is registered this way; a
 * declaration whose target isn't one of these six is simply left out of the result entirely rather
 * than failing the whole bootstrap.
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
        TsonMapperReader reader = new TsonMapperReader();
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
            Optional<Top> body = bindInstanceBody(instance, reader);
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

    private static Optional<Top> bindInstanceBody(Instance instance, TsonMapperReader reader) {
        return switch (instance.target()) {
            case "unit", "integer_type", "text_type" -> Optional.of(bindAtom(instance.value(), reader));
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
     * {@code instance.value()}'s own {@code typeRef} already names the constructor (e.g. {@code
     * "integer_type"}) -- {@code tson-bind}'s union-member resolution matches that against each
     * {@link Atom} leaf's {@code @Typename} and binds generically from there; see this class's own
     * Javadoc for why no hand-rolled name→class table is needed anywhere. Only reached for targets
     * whose entire vocabulary is representable from instance data alone -- see this class's own
     * Javadoc for why {@code uri_type}/{@code regex_type} don't qualify.
     */
    private static Top bindAtom(DataValue value, TsonMapperReader reader) {
        try {
            return reader.toObject(value, Atom.class);
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to bind constructor-application instance !"
                    + value.typeRef().orElse("?"), e);
        }
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
