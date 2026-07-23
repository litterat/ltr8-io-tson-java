dependencies {
    implementation(project(":tson-parser"))
    implementation(project(":tson-bind"))
    implementation(project(":tson-annotation"))
    implementation(project(":tson-mapper"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
