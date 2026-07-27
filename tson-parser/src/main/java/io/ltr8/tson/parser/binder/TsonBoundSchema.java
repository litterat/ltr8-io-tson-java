package io.ltr8.tson.parser.binder;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClass;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Map;

/**
 * The noun {@link TsonObjectBinder#bind} produces -- a {@link TsonLinkedSchema}'s own {@link
 * TsonSchema}, paired with the {@code tson-bind} {@link DataClass} descriptor eagerly resolved for
 * every schema type name {@link TsonObjectBinder#bind} could bind, and the {@link DataBindContext}
 * those descriptors were resolved against.
 *
 * <p>{@code boundMap} is keyed by {@link DataClass}, not the narrower {@code DataClassRecord} --
 * deliberately wider than what {@link ObjectRecordShapeFactory} (today's only consumer) needs,
 * since object-binding mode's own array/map/tuple-shaped constructors will bind through this same
 * map once they exist, not just {@code record}. {@code schema}/{@code bindContext} are carried
 * alongside the map for the same reason -- a future consumer resolving one of those other shapes
 * needs both to build its own {@link DataClass} descriptors on demand, the same way {@link
 * TsonObjectBinder#bind} did to build this map in the first place.
 */
public record TsonBoundSchema(TsonSchema schema, Map<String, DataClass> boundMap, DataBindContext bindContext) {
}
