plugins {
    id("java-library")
}

dependencies {
    // A native RFC 9485 I-Regexp engine (parser + AST; a matcher follows). A true pure leaf -- depends on
    // nothing else in the build: I-Regexp is an external standard, not TSON-specific, and the engine touches
    // only JDK Unicode data. tson-compiler depends on it (its atom vocabulary validates `regex` values and
    // `pattern` constraints), never the reverse. The engine counterpart to tson-bind, not a value model.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
