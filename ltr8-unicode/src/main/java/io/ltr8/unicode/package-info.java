/**
 * Unicode Character Database properties and the algorithms over them, each to its Unicode standard: identifier
 * properties and profiles (UAX #31), normalization forms (UAX #15, NFKC_Casefold), and the security mechanisms of
 * UTS #39 -- identifier status, confusables, the limited contexts for joining controls and restriction levels.
 *
 * <ul>
 *   <li>{@link io.ltr8.unicode.Xid} -- UAX #31's {@code XID_Start} and {@code XID_Continue}, exactly, and the
 *       Unicode version every table here is checked against.</li>
 *   <li>{@link io.ltr8.unicode.Nfc} -- Normalization Form C, allocation-free on text already in it.</li>
 *   <li>{@link io.ltr8.unicode.NfkcCasefold} -- the {@code NFKC_Casefold} mapping.</li>
 *   <li>{@link io.ltr8.unicode.Normalization} -- the text forms a string is judged and compared in.</li>
 *   <li>{@link io.ltr8.unicode.IdentifierProfile} -- a UAX #31 R1 identifier profile: its sets, its form, and
 *       which strings it admits.</li>
 *   <li>{@link io.ltr8.unicode.IdentifierStatus} -- UTS #39's {@code Identifier_Status=Allowed}.</li>
 *   <li>{@link io.ltr8.unicode.Confusables} -- UTS #39's {@code skeleton()}, which decides whether two strings
 *       are confusable.</li>
 *   <li>{@link io.ltr8.unicode.ConfusableNames} -- skeleton distinctness over a set of names.</li>
 *   <li>{@link io.ltr8.unicode.JoiningControls} -- UTS #39's limited contexts for ZWNJ and ZWJ.</li>
 *   <li>{@link io.ltr8.unicode.RestrictionLevel} -- UTS #39's restriction levels over one run of text.</li>
 *   <li>{@link io.ltr8.unicode.IdnaProperty} -- RFC 5892's IDNA2008 derived property and contextual rules.</li>
 *   <li>{@link io.ltr8.unicode.BidiRule} -- RFC 5893's Bidi rule for the labels of a domain name.</li>
 * </ul>
 *
 * <p><b>One Unicode version for every table.</b> Each table here is checked against the Unicode Character Database
 * of a single version, and the JDK's own {@link java.lang.Character} data is read only where it agrees with that
 * version, so a caller combining two of them never mixes two versions' answers.
 *
 * <p><b>Nothing here is a policy.</b> Which profile a format names with, which form a type applies, and how
 * strictly a processor refuses look-alikes are the caller's decisions; this package states what the Unicode
 * standards say a code point or a string is, and decides what a caller's chosen profile or level admits.
 */
package io.ltr8.unicode;
