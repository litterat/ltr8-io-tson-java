package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Optional;

/**
 * [TSON-SCHEMA] §5.4's discrimination classes, and the two things [TSON-JSON] adds to them: which JSON value
 * kinds each class admits (§4.2), and where that mapping <b>leaks</b> (§8.3).
 *
 * <p>The classes exist so that every encoding can state how much of a value's class its own forms recover.
 * JSON has six value kinds and the type system five classes; the null kind carries no class at all -- it is
 * the absent sentinel (§7), spent before any class question arises -- and the remaining five nearly map
 * one-to-one.
 *
 * <p><b>A type with no class needs no verdict.</b> {@code rational} and {@code complex} (both strings, but
 * §5.4 gives them no class), the {@code unit} instances, a nested choice and the {@code scoped} instances all
 * answer empty -- and a choice containing one is not disjoint ([TSON-SCHEMA] §5.4), so §8.2's route 2 never
 * consults them.
 *
 * <p><b>The derivation itself is the type system's and is shared with the TSON reader by duplication</b>, not
 * by a common class: {@code tson-compiler} keeps its own in an unexported package. The two must agree, and
 * the cross-encoding parity test is what holds them to it -- a choice that dispatches one way in text and
 * another in JSON is the drift that matters most here, since the fact is derived from the schema alone.
 */
enum DiscriminationClass {

    BOOLEAN, NUMBER, STRING, BRACE, BRACKET;

    /** The class {@code name} resolves to, following its reference chain, or empty for a type §5.4 gives none. */
    static Optional<DiscriminationClass> of(TsonSchema schema, String name) {
        return Types.terminal(schema, name).map(Types.Resolved::definition)
                .flatMap(DiscriminationClass::classify);
    }

    private static Optional<DiscriminationClass> classify(TypeDefinition definition) {
        return switch (definition.body()) {
            case IntegerType ignored -> Optional.of(NUMBER);
            case DecimalType ignored -> Optional.of(NUMBER);
            case FloatType ignored -> Optional.of(NUMBER);
            case EnumBody members -> ofEnum(members);
            case RecordBody ignored -> Optional.of(BRACE);
            case MapBody ignored -> Optional.of(BRACE);
            case ArrayBody ignored -> Optional.of(BRACKET);
            case TupleBody ignored -> Optional.of(BRACKET);
            // Every remaining atom family is string-content (§5.6). `rational`, `complex`, the `unit`
            // instances, a nested choice and `scoped` fall through to empty: §5.4 gives them no class, so
            // they can only be reached with a tag.
            case io.ltr8.tson.schema.meta.TextType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.UriType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.RegexType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.UuidType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.DateType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.TimeType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.DateTimeType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.DurationType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.PeriodType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.BytesType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.EmailType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.Ipv4Type ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.Ipv6Type ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.Cidr4Type ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.Cidr6Type ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.MacType ignored -> Optional.of(STRING);
            default -> Optional.empty();
        };
    }

    /**
     * §5.2: an enum member's form is the form of its own lexical class, so an enum's class is its members'
     * shared one -- {@code [true false]} is BOOLEAN, {@code [RED GREEN]} is STRING -- and a mixed set has
     * none, which leaves it reachable only with a tag.
     */
    private static Optional<DiscriminationClass> ofEnum(EnumBody members) {
        DiscriminationClass common = null;
        for (String member : members.members()) {
            DiscriminationClass memberClass = "true".equals(member) || "false".equals(member)
                    ? BOOLEAN
                    : STRING;
            if (common == null) {
                common = memberClass;
            } else if (common != memberClass) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(common);
    }

    /**
     * §4.2's table, read from the wire end: the class an arriving value's kind names, or empty for null,
     * which carries no class -- it is the absent sentinel and is spent before any class question arises.
     *
     * <p>The two leaks §8.3 names are <em>not</em> folded in here, and that is the point of keeping stability
     * a separate question: a string may be an approximate atom's special value and an array may be a map in
     * pairs form, but a class-stable variant set contains neither, so route 2 reads the kind straight.
     */
    static Optional<DiscriminationClass> ofKind(JsonEvent event) {
        return switch (event) {
            case JsonEvent.BooleanValue ignored -> Optional.of(BOOLEAN);
            case JsonEvent.NumberValue ignored -> Optional.of(NUMBER);
            case JsonEvent.StringValue ignored -> Optional.of(STRING);
            case JsonEvent.ObjectStart ignored -> Optional.of(BRACE);
            case JsonEvent.ArrayStart ignored -> Optional.of(BRACKET);
            default -> Optional.empty();
        };
    }

    /**
     * §8.3: whether every JSON encoding of every one of {@code name}'s values is of its own class's kind --
     * whether §4.2's mapping does not leak for it. <b>The unstable set is closed</b>, so this is a two-member
     * test and not a survey:
     *
     * <ol>
     *   <li>an approximate atom with {@code allow_nan} or {@code allow_infinity} still true has values that
     *       encode as JSON <em>strings</em> (§5.4) -- number-class values in string clothing. Narrowing both
     *       to false restores stability, which is what gives an API author a checkable reason to narrow;</li>
     *   <li>a map whose key type forces the pairs form (§6.5) encodes as a JSON <em>array</em> -- a
     *       brace-class value in bracket clothing.</li>
     * </ol>
     *
     * <p>Everything else is stable, records with a rest field included, absorption changing members and never
     * the object kind. A type with no class is not unstable -- it is unreachable by route 2 either way.
     */
    static boolean stable(TsonSchema schema, String name) {
        return Types.terminal(schema, name).map(resolved -> switch (resolved.definition().body()) {
            case FloatType floats -> !floats.allowNan() && !floats.allowInfinity();
            case MapBody map -> TreeMapReader.isObjectForm(schema, map);
            default -> true;
        }).orElse(true);
    }
}
