package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.AnnotationEnd;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.MapArrow;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays an already-built {@link DataValue} tree (e.g. an atom-refinement/{@code Instance}
 * binding's own already-normalized value, resolved entirely within {@code resolver}'s own
 * {@code DataValue}-based pipeline -- unchanged by the streaming-reader redesign, see {@link
 * DefinitionMetaReader}'s own Javadoc) as the exact {@link TsonEvent} sequence a real {@code
 * TsonDataStream} would have produced for the same source text, so a compiled reader (streaming-
 * only, {@code TsonTypeReader.read(TsonReadContext)}) can read it. Every synthesized event
 * carries the same placeholder {@link Position} -- there's no real source position to report for
 * a value that was never actually re-lexed, and nothing downstream of {@link
 * DefinitionMetaReader#read} depends on one being meaningful.
 */
final class DataValueEvents {

    private static final Position PLACEHOLDER = new Position(0, 0, 0);

    private DataValueEvents() {
    }

    static List<TsonEvent> of(DataValue value) {
        List<TsonEvent> events = new ArrayList<>();
        emitDataValue(value, events);
        return events;
    }

    private static void emitDataValue(DataValue value, List<TsonEvent> events) {
        for (Annotation annotation : value.annotations()) {
            events.add(new AnnotationStart(annotation.name(), PLACEHOLDER));
            annotation.value().ifPresent(v -> emitDataValue(v, events));
            events.add(new AnnotationEnd(PLACEHOLDER));
        }
        value.typeRef().ifPresent(ref -> events.add(new TypeRef(ref, PLACEHOLDER)));
        emitCoreValue(value.coreValue(), events);
    }

    private static void emitScopedValue(ScopedValue scopedValue, List<TsonEvent> events) {
        scopedValue.schemaRef().ifPresent(uri -> events.add(new SchemaRef(uri, PLACEHOLDER)));
        emitDataValue(scopedValue.value(), events);
    }

    private static void emitCoreValue(CoreValue core, List<TsonEvent> events) {
        switch (core) {
            case RecordValue rv -> {
                events.add(new RecordStart(PLACEHOLDER));
                for (RecordValue.Field field : rv.fields()) {
                    events.add(new FieldName(field.name(), PLACEHOLDER));
                    emitScopedValue(field.value(), events);
                }
                events.add(new RecordEnd(PLACEHOLDER));
            }
            case MapValue mv -> {
                events.add(new MapStart(PLACEHOLDER));
                for (MapValue.MapEntry entry : mv.entries()) {
                    emitDataValue(entry.key(), events);
                    events.add(new MapArrow(PLACEHOLDER));
                    emitScopedValue(entry.value(), events);
                }
                events.add(new MapEnd(PLACEHOLDER));
            }
            case ArrayValue av -> {
                events.add(new ArrayStart(PLACEHOLDER));
                for (ScopedValue element : av.elements()) {
                    emitScopedValue(element, events);
                }
                events.add(new ArrayEnd(PLACEHOLDER));
            }
            case EmptyBrace ignored -> events.add(new EmptyBraceEvent(PLACEHOLDER));
            case AbsentValue ignored -> events.add(new AbsentEvent(PLACEHOLDER));
            case TokenValue tv -> events.add(new TokenEvent(tv.text(), tv.form(), PLACEHOLDER));
        }
    }
}
