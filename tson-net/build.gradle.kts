plugins {
    id("java-library")
}

dependencies {
    // A native RFC 3986 URI / RFC 3987 IRI recognizer. A pure leaf, as tson-regex is: the grammar is an external
    // standard, not TSON's, and java.net.URI implements RFC 2396 rather than either, so nothing is delegated to it.
    // tson-base depends on it (canonical identity) and tson-atom (the uri and iri families), never the reverse.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
