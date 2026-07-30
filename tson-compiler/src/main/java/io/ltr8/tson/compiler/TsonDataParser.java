package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.Document;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.lexer.Token;
import io.ltr8.tson.compiler.lexer.TokenType;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.AnnotationEnd;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tier 3: builds a full {@code Document} AST (§2, §3, §7.4) by pulling {@link TsonEvent}s from a
 * {@link TsonDataStream} (Tier 2) and reducing that flat sequence back into the nested {@code
 * ast} tree ({@link RecordValue}, {@link MapValue}, {@link ArrayValue}, {@link EmptyBrace}, {@link
 * AbsentValue}, {@link TokenValue}). This class holds no independent implementation of the data
 * grammar -- {@link TsonDataStream} is the one place that walks source text and resolves things
 * like {@code {}} record/map disambiguation; everything here is reduction (event sequence -&gt;
 * tree), the streaming counterpart of a DOM builder sitting on top of a SAX/StAX reader.
 *
 * <p>Rejects schema documents (header containing {@code !!meta}) with {@link
 * TsonUnsupportedDocumentException} rather than attempting to parse them -- this is a Class 1
 * (data-format-only) processor (§1.5); that check lives in {@link TsonDataStream#hasNext()}, the
 * first thing it does on any document.
 *
 * <p><b>Not {@code final}, deliberately.</b> {@link TsonSchemaParser} (Part 2's schema-document
 * compiler, same package) extends this class to reuse the machinery [TSON-SCHEMA] itself says it
 * imports from [TSON-DATA] §7.4 -- {@code annotation}, {@code data-value}, directive parsing
 * (identical shape for {@code !!id}/{@code !!meta}/{@code !!import}), and the separator/adjacency
 * primitives -- rather than re-implementing identical grammar a second time. Since the schema
 * grammar has its own token types this class's event stream never models (structural operators
 * like {@code ~ ^ & | ( ) < > ? ; -}), {@link TsonSchemaParser} cannot itself be event-driven for
 * those productions -- it needs raw, single-token-at-a-time access, interleaved with calls back
 * into the shared data-grammar productions. The methods below are exactly that shared surface:
 * package-private forwards onto the same {@link TsonDataStream} instance's cursor (for the
 * schema-only tokens) and reduction entry points (for {@code annotation}/{@code data-value}/{@code
 * core-value}/directives, so {@link TsonSchemaParser} gets identical behavior without a second
 * implementation of any of them).
 */
public class TsonDataParser {

    private final TsonDataStream stream;

    public TsonDataParser(String source) {
        this.stream = new TsonDataStream(source);
    }

    public Document parseDocument() {
        List<TsonEvent> all = new ArrayList<>();
        while (stream.hasNext()) {
            all.add(stream.next());
        }
        DocumentStart start = (DocumentStart) all.get(0);
        List<TsonEvent> rootEvents = all.subList(1, all.size() - 1); // trims the trailing DocumentEnd
        DataValue root = new EventReducer(rootEvents).dataValue();
        return new Document(start.id(), start.schema(), root);
    }

    // ── Shared data-grammar entry points, reused by TsonSchemaParser ────────────────────

    DataValue parseDataValue() {
        return new EventReducer(stream.nextDataValueEvents()).dataValue();
    }

    CoreValue parseCoreValue() {
        return new EventReducer(stream.nextCoreValueEvents()).coreValue();
    }

    Annotation parseAnnotation() {
        return new EventReducer(stream.nextAnnotationEvents()).annotation();
    }

    String parseNamedDirective(String expectedName) {
        return stream.parseNamedDirective(expectedName);
    }

    String peekDirectiveName() {
        return stream.peekDirectiveName();
    }

    Token expectFieldNameToken(String context) {
        return stream.expectFieldNameToken(context);
    }

    boolean consumeSeparatorOrCloseCheck(TokenType closing) {
        return stream.consumeSeparatorOrCloseCheck(closing);
    }

    /** End position of the most recently consumed token -- the streaming stand-in for the old {@code tokens.get(pos - 1).end()}. */
    Position lastTokenEnd() {
        return stream.lastTokenEnd();
    }

    // ── Raw cursor primitives, reused by TsonSchemaParser for its own, non-data-grammar tokens ──

    Token peek() {
        return stream.peek();
    }

    Token advance() {
        return stream.advance();
    }

    boolean check(TokenType type) {
        return stream.check(type);
    }

    Token expect(TokenType type, String context) {
        return stream.expect(type, context);
    }

    TsonParseException parseError(String message) {
        return stream.parseError(message);
    }

    static String describe(Token t) {
        return TsonDataStream.describe(t);
    }

    // ── Reduction: flat TsonEvent sequence -> nested ast tree ───────────────────────────

    /**
     * Rebuilds one {@code ast} value from the flat event sequence {@link TsonDataStream} produced
     * for it. Index-based, not recursive-descent-over-tokens -- structurally the same shape the
     * old token-cursor recursion had, just consuming {@link TsonEvent}s instead of {@link Token}s.
     * A fresh instance per call (never shared across {@link #parseDataValue()}/{@link
     * #parseCoreValue()}/{@link #parseAnnotation()} invocations), so nested/repeated calls from
     * {@link TsonSchemaParser} can never corrupt one another's position.
     */
    private static final class EventReducer {

        private final List<TsonEvent> events;
        private int pos;

        EventReducer(List<TsonEvent> events) {
            this.events = events;
        }

        DataValue dataValue() {
            List<Annotation> annotations = new ArrayList<>();
            while (events.get(pos) instanceof AnnotationStart) {
                annotations.add(annotation());
            }
            Optional<String> typeRef = Optional.empty();
            if (events.get(pos) instanceof TypeRef tr) {
                typeRef = Optional.of(tr.name());
                pos++;
            }
            return new DataValue(annotations, typeRef, coreValue());
        }

        Annotation annotation() {
            AnnotationStart start = (AnnotationStart) events.get(pos++);
            Optional<DataValue> value = Optional.empty();
            if (!(events.get(pos) instanceof AnnotationEnd)) {
                value = Optional.of(dataValue());
            }
            pos++; // AnnotationEnd
            return new Annotation(start.name(), value);
        }

        CoreValue coreValue() {
            TsonEvent e = events.get(pos++);
            return switch (e) {
                case TokenEvent t -> new TokenValue(t.text(), t.form());
                case AbsentEvent ignored -> new AbsentValue();
                case EmptyBraceEvent ignored -> new EmptyBrace();
                case RecordStart ignored -> record();
                case MapStart ignored -> map();
                case ArrayStart ignored -> array();
                default -> throw new IllegalStateException("unexpected event reducing a core-value: " + e);
            };
        }

        private RecordValue record() {
            List<RecordValue.Field> fields = new ArrayList<>();
            while (!(events.get(pos) instanceof RecordEnd)) {
                FieldName name = (FieldName) events.get(pos++);
                fields.add(new RecordValue.Field(name.name(), scopedValue()));
            }
            pos++; // RecordEnd
            return new RecordValue(fields);
        }

        private MapValue map() {
            List<MapValue.MapEntry> entries = new ArrayList<>();
            while (!(events.get(pos) instanceof MapEnd)) {
                DataValue key = dataValue();
                pos++; // MapArrow
                entries.add(new MapValue.MapEntry(key, scopedValue()));
            }
            pos++; // MapEnd
            return new MapValue(entries);
        }

        private ArrayValue array() {
            List<ScopedValue> elements = new ArrayList<>();
            while (!(events.get(pos) instanceof ArrayEnd)) {
                elements.add(scopedValue());
            }
            pos++; // ArrayEnd
            return new ArrayValue(elements);
        }

        private ScopedValue scopedValue() {
            Optional<String> schemaRef = Optional.empty();
            if (events.get(pos) instanceof SchemaRef sr) {
                schemaRef = Optional.of(sr.uri());
                pos++;
            }
            return new ScopedValue(schemaRef, dataValue());
        }
    }
}
