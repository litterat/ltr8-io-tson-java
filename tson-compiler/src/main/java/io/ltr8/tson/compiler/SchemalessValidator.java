package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.Document;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.lexer.LexException;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Class 1 (schemaless) validation of a TSON data document -- checks base syntax (lexing + structure)
 * and validates any built-in / core-vocabulary typed atom ({@code !uuid}, {@code !int32}, {@code
 * !date}, ...). With no schema in scope, a type-ref that isn't one of the built-in types (§5) is
 * {@code UNKNOWN_TYPE_REF} -- there's nothing to define it. An untyped token always resolves (§4's
 * null/boolean/number/string fallback never fails), so only structure and built-in atoms can produce
 * a diagnostic.
 *
 * <p>Returns every problem found, not just the first -- the same {@link Diagnostic} shape the
 * schema-driven readers report through, so a caller renders both the same way.
 */
public final class SchemalessValidator {

    private SchemalessValidator() {
    }

    public static List<Diagnostic> validate(String source) {
        return validate(new TsonDataParser(source));
    }

    public static List<Diagnostic> validate(InputStream source) {
        return validate(new TsonDataParser(source));
    }

    private static List<Diagnostic> validate(TsonDataParser parser) {
        Document document;
        try {
            document = parser.parseDocument();
        } catch (TsonParseException | LexException | TsonUnsupportedDocumentException e) {
            return List.of(baseSyntaxError(e));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        walk(document.root(), "", parser.positions(), diagnostics);
        return diagnostics;
    }

    private static Diagnostic baseSyntaxError(RuntimeException e) {
        SourcePosition position = switch (e) {
            case TsonParseException p -> p.position();
            case LexException l -> l.position();
            case TsonUnsupportedDocumentException u -> u.position();
            default -> null;
        };
        return new Diagnostic("", Diagnostic.Code.VALIDATION_ERROR, e.getMessage(),
                "well-formed TSON", "a base-syntax error",
                Optional.ofNullable(position), Optional.empty());
    }

    private static void walk(DataValue value, String path, Map<CoreValue, Position> positions,
                             List<Diagnostic> out) {
        CoreValue core = value.coreValue();
        value.typeRef().ifPresent(name -> validateTypeRef(name, core, path, positions, out));

        switch (core) {
            case RecordValue record -> {
                for (RecordValue.Field field : record.fields()) {
                    walk(field.value().value(), path + "/" + escape(field.name()), positions, out);
                }
            }
            case ArrayValue array -> {
                int index = 0;
                for (ScopedValue element : array.elements()) {
                    walk(element.value(), path + "/" + index++, positions, out);
                }
            }
            case MapValue map -> {
                for (MapValue.MapEntry entry : map.entries()) {
                    String segment = "/" + escape(keySegment(entry.key()));
                    walk(entry.key(), path + segment, positions, out);
                    walk(entry.value().value(), path + segment, positions, out);
                }
            }
            case TokenValue ignored -> {
                // A typed token is handled above; an untyped one always resolves (§4), no diagnostic.
            }
            case EmptyBrace ignored -> {
            }
            case AbsentValue ignored -> {
            }
        }
    }

    private static void validateTypeRef(String name, CoreValue core, String path,
                                        Map<CoreValue, Position> positions, List<Diagnostic> out) {
        Optional<SourcePosition> position = Optional.ofNullable(positions.get(core));
        Optional<AtomType<?>> atomType = BuiltinTypeVocabulary.lookup(name);

        if (atomType.isEmpty()) {
            out.add(new Diagnostic(path, Diagnostic.Code.UNKNOWN_TYPE_REF,
                    "unknown type '!" + name + "' -- not a built-in type, and no schema is in scope to define it",
                    "a built-in type name", "!" + name, position, Optional.empty()));
            return;
        }
        if (!(core instanceof TokenValue token)) {
            out.add(new Diagnostic(path, Diagnostic.Code.TYPE_MISMATCH,
                    "built-in type '!" + name + "' expects a scalar value",
                    "a scalar for !" + name, core.getClass().getSimpleName(), position, Optional.empty()));
            return;
        }
        try {
            atomType.get().read(token);
        } catch (AtomTypeException e) {
            out.add(new Diagnostic(path, Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, e.getMessage(),
                    "a value satisfying !" + name, token.text(), position, Optional.empty()));
        }
    }

    /** RFC 6901 JSON Pointer escaping for a path segment. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static String keySegment(DataValue key) {
        return key.coreValue() instanceof TokenValue token ? token.text() : "?";
    }
}
