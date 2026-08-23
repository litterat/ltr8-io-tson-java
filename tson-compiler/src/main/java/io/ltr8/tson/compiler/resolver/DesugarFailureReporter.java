package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.schema.SchemaMap;

/**
 * Where {@code SchemaDesugarer} sends a declaration whose sugar form is invalid, so the rest of the document
 * still expands and resolves ([TSON-DATA] §8.1: implementations SHOULD "continue processing after an error to
 * report multiple issues in a single pass").
 *
 * <p><b>A declaration and an exception, not a {@code Diagnostic}.</b> The desugar phase is an AST-to-AST
 * rewrite that knows nothing about schema identity, canonicalization, or the identity-keyed position table --
 * all three live in {@link SchemaResolver}, which already holds them at the call site. Handing back the raw
 * pair keeps the diagnostics vocabulary out of a phase whose whole shape is "AST in, AST out", and keeps the
 * {@code Diagnostic.ofSchemaError} construction in the one place that also builds the resolver's own.
 *
 * <p>{@code declaration} is the failing declaration exactly as the parser built it -- identity matters, since
 * that is what {@code TsonSchemaParser.declarationPositions()} is keyed on, and a rewritten copy would find
 * no position there. Its {@code name()} is also the schema map's own key for it.
 *
 * <p>A {@code null} reporter means fail-fast, matching {@link SchemaResolver}'s own {@code receiver == null}
 * convention: the original exception is rethrown unwrapped, so the overloads that never took a receiver keep
 * the exact exception every existing caller sees.
 *
 * <p><b>{@code error} is a {@code RuntimeException}, not the validation exception alone</b>, because both
 * kinds of failure are reported per declaration now: an invalid sugar form (the author's error) and an
 * unimplemented construct (this library's gap). Which is which is the exception's own type, and classifying
 * it belongs with the {@code Diagnostic} construction in {@link SchemaResolver}, not here -- the same
 * division that keeps schema identity and the position table out of an AST-to-AST rewrite.
 */
@FunctionalInterface
interface DesugarFailureReporter {

    void reportFailedDeclaration(SchemaMap.Declaration declaration, RuntimeException error);
}
