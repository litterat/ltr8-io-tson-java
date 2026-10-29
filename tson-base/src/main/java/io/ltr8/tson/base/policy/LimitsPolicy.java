package io.ltr8.tson.base.policy;


import io.ltr8.tson.base.Diagnostic;

import java.util.ArrayList;
import java.util.List;

/**
 * The resource limits this processor applies to a document it reads and a schema it resolves -- [TSON-DATA]
 * §9.1's denial-of-service bounds and [TSON-SCHEMA] §11.5's, as a value a caller can read <em>before</em>
 * writing a document rather than only discover by having one refused.
 *
 * <p><b>A limit refusal is not a verdict on the document.</b> The document may be well-formed, valid, and
 * accepted in full by the next processor along; what happened is that this deployment declined to spend the
 * resources. That is why the refusal carries {@link Diagnostic.Code#LIMIT_EXCEEDED}, whose {@link
 * Diagnostic.Code#verdict()} is {@code false}, and never one of the codes that assert something about the
 * document's conformance.
 *
 * <p><b>Why the limits are a value and not a constant.</b> The argument is {@code ProcessorPolicy} 's, and for the same
 * reason: the bound is the reading deployment's own choice,
 * so the same bytes may be accepted by one server and refused by another, and that divergence is
 * unexplainable unless the configuration can be stated. A sender that can read the limits writes a document
 * that fits; one that cannot learns them one round trip too late. It is reported by the same surfaces --
 * {@code Tson.limitsPolicy()} , {@code TsonTreeReader.limitsPolicy()} , {@code TsonObjectReader.limitsPolicy()} , and
 * {@code tson policy} on the command line.
 *
 * <p><b>One policy, every encoding.</b> [TSON-JSON] §10.1 makes JSON's nesting depth, member and element
 * counts, string and number lengths and decoded binary sizes "the limits of [TSON-DATA] §9.1's limits
 * policy in JSON clothing, and the same policy applies with the same defaults" -- so this is not the TSON
 * text encoding's bound with a JSON copy beside it, it is the processor's bound, and each encoding counts
 * it where its own tokens are consumed. That is why it lives here rather than in either engine: a second
 * record would be a second default to drift.
 *
 * <p><b>The whole table, one limit counted.</b> The components are §9.1's eleven document-side limits and
 * §11.5's five schema-side ones, in that order, each defaulting to the spec's value -- the same table, names
 * and defaults as {@code policy.tn}'s {@code limits} record. Nesting depth is counted, as containers open in
 * the token stream ({@code TsonDataStream.advance}, {@code JsonStream}). The other fifteen are this policy's
 * stated thresholds: configured and reported by the same surfaces as depth, and not yet counted, so a
 * document or schema past one of them is not refused for it. {@code BACKLOG.md} carries where each is
 * counted.
 *
 * <p>Every limit is at least 1. A size in code points, octets or bytes is a {@code long}, the schema's
 * {@code int64}; a count or depth is an {@code int}, its {@code int32}.
 *
 * @param maxDepth                the deepest a container may nest before the read is refused -- see {@link
 *                                #maxDepth()}
 * @param maxTokenLength          one token's decoded text, in code points
 * @param maxTextLength           one value's text after escape processing, in code points
 * @param maxNumericLiteralLength the digits in one numeric token, annotated ({@code !number}, {@code
 *                                !rational}) or not
 * @param maxBinarySize           one {@code !bytes} value's decoded octets
 * @param maxDocumentSize         the document's bytes
 * @param maxElements             the elements of one array or set
 * @param maxEntries              the entries of one map
 * @param maxFields               the fields of one record
 * @param maxAnnotations          the annotations on one value
 * @param maxValues               all values in one document, containers and scalars alike -- an aggregate that
 *                                no per-container bound implies
 * @param maxImportClosure        the schema documents reachable from one header through {@code !!meta} and
 *                                {@code !!import}
 * @param maxSchemaEntries        the declarations in one schema map, synthetic and instantiation entries
 *                                included
 * @param maxReferenceChain       the hops from a use site to a terminal entry
 * @param maxSupertypeChain       the length of one type's {@code chain.supertypes}
 * @param maxMaterialisationDepth the nested open synthetics closed for one template application
 */
public record LimitsPolicy(int maxDepth, long maxTokenLength, long maxTextLength, int maxNumericLiteralLength,
                           long maxBinarySize, long maxDocumentSize, int maxElements, int maxEntries,
                           int maxFields, int maxAnnotations, int maxValues,
                           int maxImportClosure, int maxSchemaEntries, int maxReferenceChain,
                           int maxSupertypeChain, int maxMaterialisationDepth) {

    /**
     * The default nesting depth, 64.
     *
     * <p><b>Chosen as the lowest depth in common use rather than the most generous.</b> A default is only
     * worth having if a document written against it travels, and a document that fits the tightest common
     * limit fits every processor above it; the reverse choice makes "a conforming document" a property of
     * whoever received it. 64 is what {@code System.Text.Json} bounds at, against 128 for {@code serde_json}
     * and 1000 for Jackson, and it is what this library's one other depth guard ({@code
     * TemplateMaterialiser}'s template-closing depth) already picked independently.
     *
     * <p>It is far above what the documents this format is for actually reach -- structured output validated
     * against a schema nests in single digits -- so raising it is a deliberate act rather than the ordinary
     * case.
     */
    public static final int DEFAULT_MAX_DEPTH = 64;

    /** The default token length, 1,048,576 code points. */
    public static final long DEFAULT_MAX_TOKEN_LENGTH = 1_048_576L;

    /** The default decoded text length, 1,048,576 code points. */
    public static final long DEFAULT_MAX_TEXT_LENGTH = 1_048_576L;

    /** The default numeric literal length, 4,096 digits. */
    public static final int DEFAULT_MAX_NUMERIC_LITERAL_LENGTH = 4_096;

    /** The default decoded binary size, 16,777,216 octets. */
    public static final long DEFAULT_MAX_BINARY_SIZE = 16_777_216L;

    /** The default document size, 16,777,216 bytes. */
    public static final long DEFAULT_MAX_DOCUMENT_SIZE = 16_777_216L;

    /** The default element count of one array or set, 1,048,576. */
    public static final int DEFAULT_MAX_ELEMENTS = 1_048_576;

    /** The default entry count of one map, 1,048,576. */
    public static final int DEFAULT_MAX_ENTRIES = 1_048_576;

    /** The default field count of one record, 65,536. */
    public static final int DEFAULT_MAX_FIELDS = 65_536;

    /** The default annotation count on one value, 64. */
    public static final int DEFAULT_MAX_ANNOTATIONS = 64;

    /** The default total value count of one document, 16,777,216. */
    public static final int DEFAULT_MAX_VALUES = 16_777_216;

    /** The default import closure of one header, 64 schema documents. */
    public static final int DEFAULT_MAX_IMPORT_CLOSURE = 64;

    /** The default declaration count of one schema map, 65,536. */
    public static final int DEFAULT_MAX_SCHEMA_ENTRIES = 65_536;

    /** The default reference chain, 64 hops. */
    public static final int DEFAULT_MAX_REFERENCE_CHAIN = 64;

    /** The default supertype chain, 64 types. */
    public static final int DEFAULT_MAX_SUPERTYPE_CHAIN = 64;

    /** The default materialisation depth, 64 nested open synthetics. */
    public static final int DEFAULT_MAX_MATERIALISATION_DEPTH = 64;

    private static final LimitsPolicy DEFAULTS = new LimitsPolicy(DEFAULT_MAX_DEPTH, DEFAULT_MAX_TOKEN_LENGTH,
            DEFAULT_MAX_TEXT_LENGTH, DEFAULT_MAX_NUMERIC_LITERAL_LENGTH, DEFAULT_MAX_BINARY_SIZE,
            DEFAULT_MAX_DOCUMENT_SIZE, DEFAULT_MAX_ELEMENTS, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_FIELDS,
            DEFAULT_MAX_ANNOTATIONS, DEFAULT_MAX_VALUES, DEFAULT_MAX_IMPORT_CLOSURE,
            DEFAULT_MAX_SCHEMA_ENTRIES, DEFAULT_MAX_REFERENCE_CHAIN, DEFAULT_MAX_SUPERTYPE_CHAIN,
            DEFAULT_MAX_MATERIALISATION_DEPTH);

    /**
     * Refuses a limit below 1: it admits no document or schema at all, so it is refused where it is stated
     * rather than at the first input it silently rejects.
     */
    public LimitsPolicy {
        atLeastOne("maxDepth", maxDepth);
        atLeastOne("maxTokenLength", maxTokenLength);
        atLeastOne("maxTextLength", maxTextLength);
        atLeastOne("maxNumericLiteralLength", maxNumericLiteralLength);
        atLeastOne("maxBinarySize", maxBinarySize);
        atLeastOne("maxDocumentSize", maxDocumentSize);
        atLeastOne("maxElements", maxElements);
        atLeastOne("maxEntries", maxEntries);
        atLeastOne("maxFields", maxFields);
        atLeastOne("maxAnnotations", maxAnnotations);
        atLeastOne("maxValues", maxValues);
        atLeastOne("maxImportClosure", maxImportClosure);
        atLeastOne("maxSchemaEntries", maxSchemaEntries);
        atLeastOne("maxReferenceChain", maxReferenceChain);
        atLeastOne("maxSupertypeChain", maxSupertypeChain);
        atLeastOne("maxMaterialisationDepth", maxMaterialisationDepth);
    }

    private static void atLeastOne(String name, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, not " + value);
        }
    }

    /** The limits a processor applies when a caller has stated none: every one at the spec's default. */
    public static LimitsPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * These limits with the nesting depth replaced.
     *
     * <p><b>There is no unlimited.</b> The bound this enforces is what keeps a deeply nested document from
     * exhausting the Java stack while a reader descends it, and a {@link StackOverflowError} is an {@link
     * Error}: it passes through every {@code catch (RuntimeException)} in the reader stack, so the failure it
     * replaces is not a diagnostic at all. A depth set above what the host stack carries reintroduces exactly
     * that, which is a choice a caller may make and not one this offers a name for.
     */
    public LimitsPolicy withMaxDepth(int depth) {
        return new LimitsPolicy(depth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the token length replaced. */
    public LimitsPolicy withMaxTokenLength(long length) {
        return new LimitsPolicy(maxDepth, length, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the decoded text length replaced. */
    public LimitsPolicy withMaxTextLength(long length) {
        return new LimitsPolicy(maxDepth, maxTokenLength, length, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the numeric literal length replaced. */
    public LimitsPolicy withMaxNumericLiteralLength(int length) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, length, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the decoded binary size replaced. */
    public LimitsPolicy withMaxBinarySize(long size) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, size,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the document size replaced. */
    public LimitsPolicy withMaxDocumentSize(long size) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                size, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the element count of one array or set replaced. */
    public LimitsPolicy withMaxElements(int count) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, count, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the entry count of one map replaced. */
    public LimitsPolicy withMaxEntries(int count) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, count, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the field count of one record replaced. */
    public LimitsPolicy withMaxFields(int count) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, count, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the annotation count on one value replaced. */
    public LimitsPolicy withMaxAnnotations(int count) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, count, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the total value count of one document replaced. */
    public LimitsPolicy withMaxValues(int count) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, count,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the import closure replaced. */
    public LimitsPolicy withMaxImportClosure(int count) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                count, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the declaration count of one schema map replaced. */
    public LimitsPolicy withMaxSchemaEntries(int count) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, count, maxReferenceChain, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the reference chain replaced. */
    public LimitsPolicy withMaxReferenceChain(int hops) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, hops, maxSupertypeChain, maxMaterialisationDepth);
    }

    /** These limits with the supertype chain replaced. */
    public LimitsPolicy withMaxSupertypeChain(int length) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, length, maxMaterialisationDepth);
    }

    /** These limits with the materialisation depth replaced. */
    public LimitsPolicy withMaxMaterialisationDepth(int depth) {
        return new LimitsPolicy(maxDepth, maxTokenLength, maxTextLength, maxNumericLiteralLength, maxBinarySize,
                maxDocumentSize, maxElements, maxEntries, maxFields, maxAnnotations, maxValues,
                maxImportClosure, maxSchemaEntries, maxReferenceChain, maxSupertypeChain, depth);
    }

    /**
     * The deepest a record, map, array or tuple may nest before the read is refused, counted at the cursor as
     * containers open -- so the refusal happens before any reader descends into the value, which is the point
     * of counting it in the token stream rather than in the readers that recurse.
     *
     * <p>A schema document is counted the same way, its own schema map being the outermost container: §9.1 is
     * Part 1 and speaks of documents, but a schema is untrusted input too wherever one is fetched or reached
     * through {@code !!import}.
     */
    @Override
    public int maxDepth() {
        return maxDepth;
    }

    /** The nesting depth, then each other limit that differs from its default; the defaults go unsaid. */
    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add("max depth " + maxDepth);
        differs(parts, "max token length", maxTokenLength, DEFAULT_MAX_TOKEN_LENGTH);
        differs(parts, "max text length", maxTextLength, DEFAULT_MAX_TEXT_LENGTH);
        differs(parts, "max numeric literal length", maxNumericLiteralLength, DEFAULT_MAX_NUMERIC_LITERAL_LENGTH);
        differs(parts, "max binary size", maxBinarySize, DEFAULT_MAX_BINARY_SIZE);
        differs(parts, "max document size", maxDocumentSize, DEFAULT_MAX_DOCUMENT_SIZE);
        differs(parts, "max elements", maxElements, DEFAULT_MAX_ELEMENTS);
        differs(parts, "max entries", maxEntries, DEFAULT_MAX_ENTRIES);
        differs(parts, "max fields", maxFields, DEFAULT_MAX_FIELDS);
        differs(parts, "max annotations", maxAnnotations, DEFAULT_MAX_ANNOTATIONS);
        differs(parts, "max values", maxValues, DEFAULT_MAX_VALUES);
        differs(parts, "max import closure", maxImportClosure, DEFAULT_MAX_IMPORT_CLOSURE);
        differs(parts, "max schema entries", maxSchemaEntries, DEFAULT_MAX_SCHEMA_ENTRIES);
        differs(parts, "max reference chain", maxReferenceChain, DEFAULT_MAX_REFERENCE_CHAIN);
        differs(parts, "max supertype chain", maxSupertypeChain, DEFAULT_MAX_SUPERTYPE_CHAIN);
        differs(parts, "max materialisation depth", maxMaterialisationDepth, DEFAULT_MAX_MATERIALISATION_DEPTH);
        return String.join(", ", parts);
    }

    private static void differs(List<String> parts, String name, long value, long fallback) {
        if (value != fallback) {
            parts.add(name + " " + value);
        }
    }
}
