package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.atom.RawTokenParser;
import io.ltr8.tson.schema.meta.RecordBody;

import java.util.Map;

/**
 * Binds an <b>untagged labelled choice</b> -- a record whose fields form one REQUIRED group, exactly one of
 * which is present, onto a Java sealed interface whose members carry those fields one apiece. The kernel's
 * {@code type_argument} is the case that forces it:
 *
 * <pre>
 *   type_argument =&gt; { ( name: type_ref | value: value ) }
 * </pre>
 *
 * <p><b>The present field is the discriminator</b>, which is what separates this from {@link
 * VariantBindReader}: there is no {@code !typeName} tag to dispatch on, and §5.6's positional form makes one
 * unavailable in principle -- the kernel deliberately gives this record no positional form at all, because a
 * bare token could not say which member it was. So the record is read the ordinary way and the member is
 * chosen by what arrived.
 *
 * <p><b>Why a Java union rather than a record with two {@code Optional}s</b>, which is the more literal
 * translation: {@link io.ltr8.tson.schema.meta.TypeArgument} holds a {@code type_ref} whose {@code arguments}
 * hold {@code type_argument}s right back, and {@code tson-bind}'s record resolution has no cycle detection.
 * The union defers member resolution and breaks the loop -- see that type's own Javadoc. This reader is what
 * makes the shape chosen for the <em>write</em> side readable on the way back in.
 *
 * <p><b>Members are matched to fields by the member's own single component wire-name</b> ({@code @Field} where
 * present, the component name otherwise). A member of a labelled choice carries exactly the field it is the
 * label for, so the component <em>is</em> the field -- matching on anything else would need a second table to
 * keep in step with the first.
 */
final class GroupUnionBindReader extends RecordAbstractReader<Object> {

    /** {@code null}, a bind-mode reader having nowhere to put §2.9's "present with an absent value". */
    @Override
    Object statedAbsentValue() {
        return null;
    }

    /** Schema field name → the union member that field selects, already resolved. */
    private final Map<String, DataClassRecord> members;

    GroupUnionBindReader(String name, String displayName, RecordBody body, Map<String, DataClassRecord> members,
                          TsonTypeReaderResolver resolver, SchemaLocation schemaLocation) {
        // Per field, so a member whose own component is a Token reads the token where its sibling of the
        // same schema type reads the value -- see RecordBindReader.tokenAware for why a slot wants one.
        super(name, displayName, body, field -> {
            DataClassRecord member = members.get(field.name());
            Class<?> component = member == null ? null : member.fields()[0].type();
            return component == io.ltr8.tson.schema.meta.Token.class
                    ? AtomTypeReader.of(name, RawTokenParser.INSTANCE, schemaLocation)
                    : resolver.resolve(field.type().name());
        }, schemaLocation);
        this.members = members;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.inRecord(schemaLocation);
        ShapeResult shapeResult = expectRecordShape(ctx);
        if (shapeResult.shape() == Shape.MISMATCH) {
            return null;
        }
        int mark = ConstructionGuard.mark(ctx);
        // Sized by the schema's own fields rather than by any one member's, because which member is being
        // built is exactly what is not known until the record has been read.
        Object[] decoded = new Object[fields.size()];
        boolean[] seen = switch (shapeResult.shape()) {
            case FIELDS -> readFields(ctx, (schemaIndex, value) -> decoded[schemaIndex] = value);
            case EMPTY -> new boolean[fields.size()];
            case POSITIONAL -> readPositional(ctx, (schemaIndex, value) -> decoded[schemaIndex] = value);
            case MISMATCH -> throw new IllegalStateException("unreachable");
        };

        TsonReadContext anchoredCtx = ctx.withPosition(shapeResult.anchor());
        // The group rule is the whole contract here -- REQUIRED admits exactly one member present -- so the
        // base's own check is what guarantees the search below finds one and only one.
        validateGroups(anchoredCtx, seen);
        if (ConstructionGuard.abandoned(ctx, mark)) {
            return null; // see ConstructionGuard: bind mode never builds out of a document already reported
        }

        for (int i = 0; i < fields.size(); i++) {
            if (!seen[i]) {
                continue;
            }
            String fieldName = fields.get(i).schema().name();
            DataClassRecord member = members.get(fieldName);
            if (member == null) {
                anchoredCtx.report(Diagnostic.Code.UNRECOGNIZED_FIELD, "'" + name + "' has no bound form "
                        + "for '" + fieldName + "'", "one of " + members.keySet(), fieldName);
                return null;
            }
            return construct(member, decoded[i]);
        }
        // Unreachable where the group is REQUIRED, which is the only shape the factory builds this for --
        // validateGroups has already reported an empty record by the time control arrives here.
        return null;
    }

    private Object construct(DataClassRecord member, Object value) {
        try {
            return member.constructor().invoke(new Object[] {value});
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to construct " + member.typeClass() + " from '" + name
                    + "'s own labelled-choice value", t);
        }
    }

    /** The wire name of a member's single component -- the schema field that member is the label for. */
    static String labelOf(DataClassRecord member) {
        DataClassField[] components = member.fields();
        return components.length == 1 ? components[0].name() : null;
    }
}
