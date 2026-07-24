dependencies {
    implementation(project(":tson-schema"))
    implementation(project(":tson-annotation"))
    // tson-bind has no dependency on tson-parser/tson-schema (a leaf module), so depending on it
    // here directly is clean -- needed in main scope now that TsonMapperReader/TsonMapperWriter
    // (originally in the separate tson-mapper module, which depended on tson-parser and so could
    // never be depended on back) live in this module instead, so schema resolution can bind a
    // DataValue onto a schema.meta class without a module cycle.
    implementation(project(":tson-bind"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// MetaKernelParser needs meta-kernel.tn1 on the classpath at runtime (it bootstraps meta-kernel's
// own resolved schema, which can't be resolved from nothing -- see MetaKernelParser's own Javadoc).
// Packaged straight from the repo's own spec/ snapshot rather than a duplicated copy under
// src/main/resources, so there is exactly one file to keep in sync with the spec.
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("spec/m")) {
        include("meta-kernel.tn1")
    }
}
