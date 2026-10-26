plugins {
    id("java-library")
}

dependencies {
    // Native recognizers for network text formats, each an external standard rather than TSON's: RFC 3986 URIs and
    // RFC 3987 IRIs (java.net.URI implements RFC 2396 rather than either, so nothing is delegated to it), addresses,
    // and IDNA2008 host names. tson-base depends on it (canonical identity) and tson-atom (the uri, iri, address and
    // host families), never the reverse.
    //
    // ltr8-unicode is its one dependency: IDNA2008's validity is Unicode's derived property and the Bidi rule,
    // which belong with the rest of the Unicode tables at one version. `implementation`: no public signature here
    // names one of its types.
    implementation(project(":ltr8-unicode"))
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
