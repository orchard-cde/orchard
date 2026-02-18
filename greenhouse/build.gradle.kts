plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":roots"))
    api(project(":harvest"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
