/**
 * The kernel's {@code unicode} namespace in the schema model: the text families, each a constraint record of the
 * kernel's {@code *_type} shape -- {@link io.ltr8.tson.schema.unicode.TextType} ({@code unicode.text_type}), {@link
 * io.ltr8.tson.schema.unicode.IdentifierType} ({@code unicode.identifier_type}) and {@link
 * io.ltr8.tson.schema.unicode.RegexType} ({@code unicode.regex_type}) -- and {@link
 * io.ltr8.tson.schema.unicode.TextFamily}, what every family composing {@code unicode.text_type} shares: its value is
 * a token's text in the family's {@code normalization} form.
 *
 * <p>A class here is bound by its entry's path, {@code unicode.text_type} to {@code TextType}, as every name of the
 * kernel binds into the package of the namespace that declares it.
 */
package io.ltr8.tson.schema.unicode;
