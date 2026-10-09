/**
 * Native recognizers for network text formats, each to its RFC -- see {@link io.ltr8.net}.
 */
module io.ltr8.net {
    // IDNA2008's derived property and RFC 5893's Bidi rule, at the one Unicode version every table shares.
    requires io.ltr8.unicode;

    exports io.ltr8.net;
}
