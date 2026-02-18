plugins {
    `java-library`
}

dependencies {
    api(project(":core"))

    implementation("org.slf4j:slf4j-api:2.0.16")
}
