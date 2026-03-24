plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":roots"))
    api(project(":harvest"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
