plugins {
    `java-library`
}

dependencies {
    api(project(":core"))

    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
