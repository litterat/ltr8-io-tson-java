package io.ltr8.tson.schema.meta;

import java.time.Duration;
import java.time.Period;

/**
 * The meta-kernel's {@code duration} host value -- ISO 8601's {@code PnYnMnDTnHnMnS}, which no
 * single {@code java.time} type covers: {@link Period} handles the calendar part ({@code Y}/{@code
 * M}/{@code D}) but rejects any {@code T}-time part at all ({@code Period.parse("PT1H")} throws),
 * and {@link Duration} handles the clock part ({@code H}/{@code M}/{@code S}, plus {@code D} as a
 * fixed 24-hour unit with no calendar semantics) but rejects any {@code Y}/{@code M} outright
 * ({@code Duration.parse("P1Y")} throws) -- confirmed empirically before writing {@code
 * tson-parser}'s {@code DurationParser}, not assumed. This pairs the two rather than inventing six
 * raw fields of its own, reusing each type's own (correct, JDK-provided) equality and arithmetic
 * for its half instead of rebuilding it. Lives in {@code schema.meta}, not {@code tson-parser},
 * because {@code DurationType}'s own {@code min}/{@code max} constraint fields are typed as this.
 *
 * <p>Not {@link Comparable} -- core.tn1 marks {@code duration}'s ordering {@code @ordered:PARTIAL}
 * precisely because a calendar-based duration ({@code P1M}, one calendar month) has no fixed length
 * to compare against a clock-based one (`P1M` may be 28-31 days depending on when it's applied) --
 * min/max bounds on {@code duration_type} are modeled on {@link DurationType} for structural fidelity
 * but not validated, since no built-in instance sets them and a coherent partial-order comparison
 * isn't implemented here.
 */
public record IsoDuration(Period calendarPart, Duration clockPart) {
}
