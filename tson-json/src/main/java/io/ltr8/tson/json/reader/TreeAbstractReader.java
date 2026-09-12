package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.FamilyDiagnostics;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.Set;

/**
 * An ABSTRACT record position: [TSON-JSON] §6.1.5's second reading. The record has no direct instances, so
 * there is no value for an untagged object to be and {@code $type} is <b>REQUIRED</b> -- the failure lands
 * before the members are read, which is the same refusal TSON text gives a missing {@code !variant}.
 *
 * <p><b>A separate reader rather than a branch, because the position reads differently rather than reading
 * more.</b> Nothing here decodes a member: this class picks a type and hands the whole object to that type's
 * reader. Deciding that once, when the schema compiles, is what the extension fact is for -- a concrete
 * record's reader never asks whether it is abstract, and this one never carries a field list it cannot use.
 */
final class TreeAbstractReader implements JsonTypeReader<JsonValue> {

    private final String displayName;
    private final Set<String> subtypes;
    private final TypeReaderResolver readerFor;
    private final JsonSchemaLocation schemaLocation;
    private final FamilyDiagnostics family;
    private final RecordDiagnostics rules;

    TreeAbstractReader(String displayName, Set<String> subtypes, TypeReaderResolver readerFor,
                        JsonSchemaLocation schemaLocation, RecordDiagnostics rules) {
        this.displayName = displayName;
        this.subtypes = subtypes;
        this.readerFor = readerFor;
        this.schemaLocation = schemaLocation;
        this.rules = rules;
        this.family = new FamilyDiagnostics(displayName, String.join(" | ", subtypes));
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        ctx = ctx.inRecord(schemaLocation);
        if (!(ctx.peek() instanceof JsonEvent.ObjectStart)) {
            JsonEvent found = ctx.next();
            ctx.report(rules.notARecord(JsonAtoms.describe(found)));
            EventSkip.value(ctx, found);
            return JsonNull.INSTANCE;
        }
        ReservedMembers.Tag tag = ReservedMembers.scan(ctx);
        JsonValue refused = Tags.refuseMisuse(ctx, tag, displayName);
        if (refused != null) {
            return refused;
        }
        if (tag.type() == null) {
            // Before the members: §6.1.5 is explicit that nothing about the object's shape is consulted, so
            // an abstract position with no tag fails whatever it holds.
            ctx.report(family.tagRequired(ReservedMembers.TYPE));
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (!subtypes.contains(tag.type())) {
            // The base itself is not admissible here, which is the whole of what ABSTRACT means -- so this
            // refuses a tag naming it, where a concrete position would take one as a redundant restatement.
            if (!NameHygiene.refuses(ctx, tag.type())) {
                ctx.field(ReservedMembers.TYPE).report(io.ltr8.tson.base.Diagnostic.Code.TYPE_MISMATCH,
                        "'$type' names '%s', which is not a subtype of the abstract '%s' -- a value here is a "
                                .formatted(tag.type(), displayName) + "value of one of its subtypes "
                                + "([TSON-SCHEMA] §5.2, §7.2)",
                        String.join(" | ", subtypes), tag.type());
            }
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        return tag.wrapper()
                ? ReservedMembers.readWrapped(ctx, readerFor.resolve(tag.type()))
                : (JsonValue) readerFor.resolve(tag.type()).read(ctx);
    }
}
