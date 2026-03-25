plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":roots"))
    api(project(":harvest"))
    api(project(":nursery"))
    api(project(":greenhouse"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
