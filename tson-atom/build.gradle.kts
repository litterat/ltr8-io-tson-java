plugins {
    id("java-library")
}

dependencies {
    // The atom families are the type system's, not the text encoding's: [TSON-JSON] §5.1 hands a JSON
    // string's content to the atom's own parser exactly as a TSON quoted token's text would be, so both
    // encodings read the same families to the same host values. This module is that vocabulary, over the
    // three things it genuinely needs and nothing else.
    //
    // `schema.meta`'s constraint records are what a parser holds (an IntegerParser holds an IntegerType),
    // and `schema.atom`'s host values are what several of them produce -- so `tson-schema` is api, not
    // implementation: a caller naming AtomParsers names a `Top` at the call site.
    api(project(":tson-schema"))
    api(project(":tson-base"))

    // TSON pins its `regex` atom to I-Regexp (RFC 9485), so RegexParser validates through the native
    // engine rather than through java.util.regex, which is a laxer superset.
    implementation(project(":tson-regex"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
