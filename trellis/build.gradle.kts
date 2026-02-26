plugins {
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native") version "0.11.4"
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("orchard-server")
            mainClass.set("dev.orchard.trellis.OrchardApplication")
            buildArgs.add("--no-fallback")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":roots"))
    implementation(project(":harvest"))
    implementation(project(":nursery"))
    implementation(project(":greenhouse"))
    implementation(project(":api"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("com.h2database:h2")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}
