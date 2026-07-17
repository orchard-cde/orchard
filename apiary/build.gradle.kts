plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":nursery"))
    testRuntimeOnly("org.slf4j:slf4j-simple")
}
