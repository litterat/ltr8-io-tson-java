package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/**
 * One structural event in Tier 2's flat, pull-based decomposition of a data document (§2, §3,
 * §7.4) -- the streaming counterpart to {@code io.ltr8.tson.compiler.ast}'s nested {@code
 * Document}/{@code DataValue} tree. {@code io.ltr8.tson.compiler.TsonDataStream} emits these
 * lazily, one token of lookahead at a time (two, only to resolve the {@code {}} record/map
 * ambiguity -- see its own Javadoc), so a consumer can rebuild the identical AST Tier 3 builds
 * today without this layer ever holding more than one open container's worth of state in memory.
 *
 * <p>Every value position (document root, record field, map key/value, array element, an
 * annotation's own value) has the same self-delimiting shape in the stream: zero or more
 * {@link AnnotationStart}/{@link AnnotationEnd} pairs, an optional {@link TypeRef}, then exactly
 * one core-value -- either a single leaf event ({@link TokenEvent}, {@link AbsentEvent}, {@link
 * EmptyBraceEvent}) or a matched {@link RecordStart}/{@link RecordEnd}, {@link MapStart}/{@link
 * MapEnd}, or {@link ArrayStart}/{@link ArrayEnd} pair.
 */
public sealed interface TsonEvent
        permits DocumentStart, DocumentEnd, AnnotationStart, AnnotationEnd, TypeRef, SchemaRef,
        RecordStart, FieldName, RecordEnd, MapStart, MapArrow, MapEnd, ArrayStart, ArrayEnd,
        TokenEvent, AbsentEvent, EmptyBraceEvent {

    Position position();
}
