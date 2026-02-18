plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":roots"))
    api(project(":harvest"))
    api(project(":nursery"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}
