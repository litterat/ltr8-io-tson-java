package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.Top;

import java.util.Optional;

/**
 * [TSON-SCHEMA] §5.4's discrimination classes, and what [TSON-JSON] adds to them: which JSON value kind each class
 * admits (§4.2).
 *
 * <p>The classes exist so that every encoding can state how much of a value's class its own forms recover.
 * JSON has six value kinds and the type system five classes; the null kind carries no class at all -- it is
 * the void sentinel (§7), spent before any class question arises -- and the remaining five map one-to-one.
 *
 * <p><b>A type with no class needs no verdict.</b> {@code rational} and {@code complex} (both strings, but
 * §5.4 gives them no class), {@code value} and {@code void}, a nested choice and the {@code declared_type} instances all
 * answer empty -- and a choice containing one is not disjoint ([TSON-SCHEMA] §5.4), so §8.2's untagged route never
 * consults them. So do §5.4's two straddling kinds: an approximate atom whose {@code allow_nan} or
 * {@code allow_infinity} is true, whose special values are JSON strings (§5.4), and a map whose key type takes the
 * pairs form (§6.4), a JSON array. Every classed type is therefore class-stable (§8.3): each of its values arrives
 * as its own class's kind.
 *
 * <p><b>The derivation itself is the type system's and is shared with the TSON reader by duplication</b>, not
 * by a common class: {@code tson-compiler} keeps its own in an unexported package. The two must agree, and
 * the cross-encoding parity test is what holds them to it -- a choice that dispatches one way in text and
 * another in JSON is the drift that matters most here, since the fact is derived from the schema alone.
 */
enum DiscriminationClass {

    BOOLEAN, NUMBER, STRING, BRACE, BRACKET;

    /** The class {@code name} resolves to, following its alias chain, or empty for a type §5.4 gives none. */
    static Optional<DiscriminationClass> of(TsonLinkedSchema linked, String name) {
        return AliasChain.terminal(linked.schema(), name)
                .flatMap(resolved -> classify(linked.schema(), resolved.definition()));
    }

    private static Optional<DiscriminationClass> classify(TsonSchema schema, Top definition) {
        return switch (definition) {
            case IntegerType ignored -> Optional.of(NUMBER);
            case DecimalType ignored -> Optional.of(NUMBER);
            case FloatType floats -> floats.allowNan() || floats.allowInfinity()
                    ? Optional.empty() : Optional.of(NUMBER);
            case EnumBody members -> ofEnum(members);
            case RecordBody ignored -> Optional.of(BRACE);
            case MapBody map -> MapPlan.isObjectForm(schema, map) ? Optional.of(BRACE) : Optional.empty();
            case ArrayBody ignored -> Optional.of(BRACKET);
            case TupleBody ignored -> Optional.of(BRACKET);
            // Every remaining atom family is string-content (§5.6). `rational`, `complex`, `value`,
            // `void`, a nested choice and `declared` fall through to empty: §5.4 gives them no class, so
            // they can only be reached with a tag.
            case io.ltr8.tson.schema.meta.TextType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.IdentifierType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.RefBody ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.KindBody ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.UriType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.IriType ignored -> Optional.of(STRING);
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
            case io.ltr8.tson.schema.meta.HostnameType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.HostType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.MediaTypeType ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.Ipv6Type ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.Cidr4Type ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.Cidr6Type ignored -> Optional.of(STRING);
            case io.ltr8.tson.schema.meta.MacType ignored -> Optional.of(STRING);
            default -> Optional.empty();
        };
    }

    /**
     * §5.2, for an enum over an identifier family: a member's form is the form of its own lexical class, so the
     * enum's class is its members' shared one -- {@code [true false]} is BOOLEAN, {@code [RED GREEN]} is
     * STRING -- and a mixed set has none, which leaves it reachable only with a tag.
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
     * which carries no class -- it is the void sentinel and is spent before any class question arises. Every
     * classed type is class-stable (§8.3), so the untagged route reads the kind straight.
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
}
