plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    implementation(project(":nursery"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
