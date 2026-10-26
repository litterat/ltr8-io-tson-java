# ltr8-unicode — Unicode properties and algorithms (`io.ltr8.unicode`)

Unicode Character Database properties and the algorithms over them, each to its Unicode standard: identifier
properties and profiles (UAX #31), normalization forms (UAX #15, NFKC_Casefold) and UTS #39's security mechanisms —
identifier status, confusable skeletons over a set, joining-control contexts and restriction levels — and IDNA2008's
derived property (RFC 5892) and Bidi rule (RFC 5893). Depends on
nothing and knows nothing of TSON, so it carries no `Tson` prefix and is usable on its own. What a format chooses
— its own profile, the policy it applies, the words of a refusal — belongs to the module that defines the format
(`tson-atom`'s `IdentifierGrammar`, `tson-base`'s `ScriptPolicy` and `IdentifierPolicy`, for TSON's).

- One Unicode version for every table, checked against that version's UCD files. A JDK whose Unicode version
  moves needs every table re-derived here, together.
- A JDK property is used only where it agrees with that version; where it does not, or the JDK lacks the
  property, the table is carried here.
- No policy: the caller chooses the profile, the level and the form; this module answers what that choice admits.
