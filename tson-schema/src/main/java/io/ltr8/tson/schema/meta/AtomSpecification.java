package io.ltr8.tson.schema.meta;

import java.net.URI;

/**
 * The meta-kernel's {@code atom_specification} mixin record (Part 2 §5.5: {@code atom_specification
 * => { spec: uri } }) -- one REQUIRED field, {@code spec}, citing the RFC/ISO document an atom
 * constructor's format claims to implement. Composed (via {@code &}) into every atom constructor
 * that cites an external spec rather than being self-describing: {@code uri_type => ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc3986" ... } } and {@code
 * regex_type => ~text_type & atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc9485"
 * } }. Unlike {@code access_pattern}/{@code size_type} (fixed per constructor but never varying
 * *and* carrying no distinguishing information -- see {@link RecordBody}'s own Javadoc for why
 * those are omitted entirely), {@code spec}'s fixed value genuinely differs from one composing
 * constructor to the next (RFC 3986 vs. RFC 9485), so it's kept as real data here rather than
 * dropped as always-implied.
 */
public record AtomSpecification(URI spec) {
}
