plugins {
    id("java-library")
}

dependencies {
    // A JSON stack of its own -- lexer, event stream, tree, readers -- rather than a second front end over
    // `tson-compiler`'s `TsonEventSource`. A true pure leaf so far: RFC 8259 is an external standard, and
    // nothing in the lexical or structural layer is TSON-specific. The schema-directed decode of
    // [TSON-JSON] §5-§8 is what will bring a dependency on `tson-compiler`; the JSON layers under it
    // stay usable, and testable, without one.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
