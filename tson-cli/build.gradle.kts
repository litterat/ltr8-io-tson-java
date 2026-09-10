plugins {
    id("application")
}

application {
    mainClass.set("io.ltr8.tson.cli.TsonCli")
    // The installed command is `tson` (matching its own usage text), not the Gradle module name
    // `tson-cli`. Unrelated to the TsonCli class or the io.ltr8.tson.Tson library facade.
    applicationName = "tson"
}

dependencies {
    implementation(project(":tson-base"))
    implementation(project(":tson"))
    implementation(project(":tson-compiler"))
    implementation(project(":tson-schema"))

    // The JSON encoding, so `validate` can read a .json input against a TSON schema
    // ([TSON-JSON] §3.4's out-of-band binding). One CLI over both encodings is the point:
    // the report, the exit codes and the policy field are the run's, not an encoding's.
    implementation(project(":tson-json"))
    implementation(project(":tson-bind"))
    implementation(project(":tson-annotation"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
