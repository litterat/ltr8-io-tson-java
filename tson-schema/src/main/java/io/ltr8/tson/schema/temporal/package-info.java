/**
 * The {@code temporal} namespace in the schema model, which temporal.tn declares: {@link
 * io.ltr8.tson.schema.temporal.DateType}, {@link io.ltr8.tson.schema.temporal.DateTimeType}, {@link
 * io.ltr8.tson.schema.temporal.TimeType}, {@link io.ltr8.tson.schema.temporal.DurationType} and {@link
 * io.ltr8.tson.schema.temporal.PeriodType}, each a constraint record of the kernel's {@code *_type} shape.
 *
 * <p>A class here is bound by its entry's path, {@code temporal.date_type} to {@code DateType}, as every name of the
 * kernel and its libraries binds into the package of the namespace that declares it; {@code temporal.datetime_type}
 * reaches {@code DateTimeType} through the binder's alias table.
 */
package io.ltr8.tson.schema.temporal;
