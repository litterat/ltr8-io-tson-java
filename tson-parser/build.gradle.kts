dependencies {
    implementation(project(":tson-schema"))
    implementation(project(":tson-annotation"))

    testImplementation(project(":tson-bind"))
    testImplementation(project(":tson-mapper"))
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
