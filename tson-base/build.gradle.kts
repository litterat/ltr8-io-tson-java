plugins {
    id("java-library")
}

dependencies {
    // A general binding engine with no knowledge of TSON -- the same standing as `tson-regex` and the
    // JDK's own `java.net.http`, both of which this module already rests on. `base.bind` states what a
    // deployment binds with; the engine that does it is a system library, not a layer above this one.
    api(project(":tson-bind"))
    // `io.ltr8.bind` requires it transitively, so the module path needs it here even though nothing in
    // this module names one of its types -- `tson-bind` declares it `implementation`, which does not
    // propagate. `tson-compiler` and `tson-json` carry the same line for the same reason.
    implementation(project(":tson-annotation"))

    // The bottom of the stack: what every encoding and every phase reports through, and the position type
    // a report points at. A true pure leaf -- depends on nothing, and nothing here knows what a TSON
    // document or a JSON one looks like. That is the point: a diagnostic vocabulary shared by two encodings
    // has to be reachable from both without either dragging the other in.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
