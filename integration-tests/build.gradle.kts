plugins {
    java
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations["implementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

dependencies {
    "integrationTestRuntimeOnly"("com.h2database:h2")

    "integrationTestImplementation"("info.picocli:picocli:4.7.7")
    "integrationTestImplementation"("org.assertj:assertj-core:3.27.7")
    "integrationTestImplementation"("org.awaitility:awaitility:4.3.0")
    "integrationTestImplementation"(platform("org.junit:junit-bom:6.1.2"))
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-restclient")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-resttestclient")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
    "integrationTestImplementation"("org.testcontainers:testcontainers:2.0.5")
    "integrationTestImplementation"("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    "integrationTestImplementation"("org.testcontainers:testcontainers-localstack:2.0.5")
    "integrationTestImplementation"(platform("software.amazon.awssdk:bom:2.49.1"))
    "integrationTestImplementation"("software.amazon.awssdk:ec2")
    "integrationTestImplementation"("software.amazon.awssdk:url-connection-client")
    implementation(project(":trellis"))
    implementation(project(":trowel"))
    implementation(project(":core"))
    implementation(project(":nursery"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests with real QEMU VMs"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "15m")
}
