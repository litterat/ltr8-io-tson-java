plugins {
    id("java-library")
}

dependencies {
    // A pure, immutable value model (TsonNode and its node types) -- no dependency on anything else in
    // the build, not even tson-annotation: the nodes aren't bind targets, they're produced by hand-written
    // readers in tson-compiler. A true leaf, the data-tree counterpart to tson-schema's schema.meta model.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
