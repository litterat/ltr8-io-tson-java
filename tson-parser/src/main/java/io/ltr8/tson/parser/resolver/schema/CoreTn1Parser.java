package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves core.tn1's own source text into an (unregistered) {@link TsonSchema} -- the TSON core
 * type library (Part 2's own worked "canonical types for data interchange" schema), the rung above
 * meta.tn1 on the schema ladder. Unlike meta.tn1, core.tn1 declares no {@code !!import} of its own
 * -- every declaration is a constructor application or atom refinement built purely against its
 * {@code !!meta} target's own structure namespace (§3.3.1), never a local or imported type-name --
 * so this resolves each declaration via {@code SchemaResolver}'s three-argument overload, threading
 * {@code registeredMeta}'s own entries as that structure namespace, in a single source-order pass
 * (core.tn1's own declaration order, like meta.tn1's, already places each dependency before its
 * use -- no two-pass forward-reference handling needed here either).
 *
 * <p><b>{@code registeredMeta} MUST be meta.tn1's own already-*registered* result, not the raw
 * {@link MetaTn1Parser#parse} output.</b> Unlike {@link MetaTn1Parser} (which only ever needs
 * meta-kernel's own real, locally-declared constructors), core.tn1 relies on names meta.tn1 only
 * carries after registration merges in its own {@code !!import} of meta-kernel -- {@code void =>
 * !unit {}}'s own {@code source: unit} is exactly this case: {@code unit} is a meta-kernel
 * constructor, present in meta.tn1's *registered* entries (via its import) but absent from
 * meta.tn1's raw, freshly-resolved 31 entries alone.
 *
 * <p><b>One declaration, {@code boolean}, is hand-picked rather than resolved generically</b> --
 * core.tn1's own local {@code boolean => !enum [true false]} redeclaration hits the identical,
 * permanent generic-binding limitation already documented for meta-kernel's own {@code boolean}
 * (see {@link MetaKernelParser}'s own Javadoc, and {@code SPEC-FEEDBACK.md}): {@code "true"}/
 * {@code "false"} collide with TSON's own boolean-literal shape and get identified as actual
 * booleans by base type resolution before {@code EnumBody.members} ever sees them. Handled the same
 * way meta-kernel's own {@code boolean} is -- {@link MetaKernelParser#toEnumBody} reads each
 * member's raw token text directly, bypassing base-type identification entirely -- rather than
 * silently dropping the entry, matching this codebase's own "known gap, not silently mishandled"
 * convention.
 */
public final class CoreTn1Parser {

    private CoreTn1Parser() {
    }

    /** Parses the core.tn1 source bundled with this module, against {@code registeredMeta}'s own structure namespace. */
    public static TsonSchema parse(TsonSchema registeredMeta) {
        return parse(readBundledSource(), registeredMeta);
    }

    public static TsonSchema parse(String source, TsonSchema registeredMeta) {
        SchemaDocument document = new SchemaParser(source).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver();
        Map<String, TypeDefinition> structureNamespace = registeredMeta.entries();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();

        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            resolved.put(declaration.name(),
                    resolveDeclaration(declaration, resolved, structureNamespace, resolver));
        }
        return new TsonSchema(document.id(), document.meta(), document.imports(), resolved);
    }

    private static TypeDefinition resolveDeclaration(SchemaMap.Declaration declaration,
                                                       Map<String, TypeDefinition> resolved,
                                                       Map<String, TypeDefinition> structureNamespace,
                                                       SchemaResolver resolver) {
        if (declaration.typeDef() instanceof Instance instance && "enum".equals(instance.target())
                && "boolean".equals(declaration.name())) {
            TypeDefinition enumConstructor = structureNamespace.get("enum");
            if (enumConstructor == null) {
                throw new IllegalStateException(
                        "'enum' is not visible in core.tn1's own structure namespace -- is registeredMeta really meta.tn1's registered result?");
            }
            return new TypeDefinition(Optional.of(TypeRef.of("enum")), enumConstructor.kind(),
                    List.of(), false, List.of(), List.of(), Optional.empty(), MetaKernelParser.toEnumBody(instance.value()));
        }
        return resolver.resolve(declaration, resolved, structureNamespace);
    }

    private static String readBundledSource() {
        try (InputStream in = CoreTn1Parser.class.getResourceAsStream("/core.tn1")) {
            if (in == null) {
                throw new IOException("core.tn1 not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
