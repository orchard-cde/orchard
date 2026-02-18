plugins {
    `java-library`
}

dependencies {
    api(project(":core"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
