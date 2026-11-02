/**
 * The {@code numeric} namespace in the schema model, the kernel's members of it and numeric.tn's: {@link
 * io.ltr8.tson.schema.numeric.IntegerType} ({@code numeric.integer_type}) and its {@link
 * io.ltr8.tson.schema.numeric.IntegerSize}, declared by the kernel, and {@link
 * io.ltr8.tson.schema.numeric.DecimalType}, {@link io.ltr8.tson.schema.numeric.FloatType}, {@link
 * io.ltr8.tson.schema.numeric.RationalType} and {@link io.ltr8.tson.schema.numeric.ComplexType}, which numeric.tn
 * adds. Each is a constraint record of the kernel's {@code *_type} shape; the host values they read to are {@code
 * tson-base}'s ({@code io.ltr8.tson.base.atom}).
 *
 * <p>A class here is bound by its entry's path, {@code numeric.integer_type} to {@code IntegerType}, as every name of
 * the kernel and its libraries binds into the package of the namespace that declares it.
 */
package io.ltr8.tson.schema.numeric;
