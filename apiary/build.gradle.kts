plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    testRuntimeOnly("org.slf4j:slf4j-simple")
}
