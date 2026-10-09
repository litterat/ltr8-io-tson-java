plugins {
    id("java-library")
}

dependencies {
    // Unicode Character Database properties and the algorithms over them, to the Unicode standards and nothing
    // else. A pure leaf, as tson-regex and tson-net are: it knows nothing of TSON, so every module that reads
    // Unicode data -- tson-base's identifier profile and name hygiene, and tson-net's host names -- shares one
    // set of tables at one Unicode version, and none depends on another to reach them.
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
