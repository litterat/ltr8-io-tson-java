plugins {
    id("java-library")
}

dependencies {
    // The bottom of the stack: what every encoding and every phase reports through, and the position type
    // a report points at. A true pure leaf -- depends on nothing, and nothing here knows what a TSON
    // document or a JSON one looks like. That is the point: a diagnostic vocabulary shared by two encodings
    // has to be reachable from both without either dragging the other in.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
