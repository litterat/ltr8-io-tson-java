package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves meta.tn1's own source text into an (unregistered) {@link TsonSchema} -- the canonical
 * meta-schema for user schemas (Part 2 §9), the rung directly above the meta-kernel on the schema
 * ladder. Unlike {@link MetaKernelParser} (which produces the *pre-loaded* {@link MetaSchema} --
 * meta-kernel's own {@code !!meta} names itself, §1.5's deliberate circularity), meta.tn1 resolves
 * the ordinary way: every one of its 31 declarations resolves in a single source-order pass,
 * because meta.tn1's own declaration order already places each dependency before its use (unlike
 * meta-kernel, this class needs no two-pass forward-reference handling).
 *
 * <p><b>Returns unregistered, exactly like {@link MetaKernelParser#parse()}.</b> The caller runs it
 * through a {@code SchemaRegistry} separately -- registration is what actually merges in
 * meta-kernel's own entries (via meta.tn1's real {@code !!import}) and materializes any
 * argument-bearing type-refs; this class only resolves the 31 locally-declared entries themselves.
 *
 * <p><b>The namespace {@link #parse} resolves against is the passed-in {@code metaKernel}'s own
 * {@link MetaSchema#entries()} directly</b> -- deliberately the *unregistered* meta-kernel result,
 * not a registered/materialized one. Meta.tn1's own resolution only ever needs meta-kernel's real,
 * named constructors (never one of the synthesized array-sugar placeholder entries registration
 * would add), so the smaller, unregistered namespace is sufficient and simpler to obtain (no
 * {@code SchemaRegistry} dependency needed just to resolve meta.tn1 itself). A caller assembling the
 * full library still registers meta-kernel first regardless, since {@code CoreTn1Parser} (one rung
 * up) needs meta.tn1's own *registered* result as its structure namespace.
 */
public final class MetaTn1Parser {

    private MetaTn1Parser() {
    }

    /** Parses the meta.tn1 source bundled with this module, against {@code metaKernel}'s own entries. */
    public static TsonSchema parse(MetaSchema metaKernel) {
        return parse(readBundledSource(), metaKernel);
    }

    public static TsonSchema parse(String source, MetaSchema metaKernel) {
        SchemaDocument document = new SchemaParser(source).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver();
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaKernel.entries());
        Map<String, TypeDefinition> localOnly = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            TypeDefinition resolved = resolver.resolve(declaration, namespace);
            namespace.put(declaration.name(), resolved);
            localOnly.put(declaration.name(), resolved);
        }
        return new TsonSchema(document.id(), document.meta(), document.imports(), localOnly);
    }

    private static String readBundledSource() {
        try (InputStream in = MetaTn1Parser.class.getResourceAsStream("/meta.tn1")) {
            if (in == null) {
                throw new IOException("meta.tn1 not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
