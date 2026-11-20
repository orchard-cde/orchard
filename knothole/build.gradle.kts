plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    implementation("org.slf4j:slf4j-api")

    testRuntimeOnly("org.slf4j:slf4j-simple")
}
