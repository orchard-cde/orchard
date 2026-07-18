plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":nursery"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testRuntimeOnly("org.slf4j:slf4j-simple")
}
