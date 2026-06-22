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
    implementation(project(":trellis"))
    implementation(project(":trowel"))
    implementation(project(":core"))
    implementation(project(":api"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    "integrationTestImplementation"("info.picocli:picocli:4.7.7")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-starter-test")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-resttestclient")
    "integrationTestImplementation"("org.springframework.boot:spring-boot-restclient")
    "integrationTestImplementation"("org.awaitility:awaitility:4.3.0")
    "integrationTestImplementation"("org.testcontainers:testcontainers:1.21.4")
    "integrationTestImplementation"("org.testcontainers:junit-jupiter:1.21.4")
    "integrationTestImplementation"("org.testcontainers:localstack:1.21.4")
    "integrationTestImplementation"(platform("software.amazon.awssdk:bom:2.46.15"))
    "integrationTestImplementation"("software.amazon.awssdk:ec2")
    "integrationTestImplementation"("software.amazon.awssdk:url-connection-client")
    "integrationTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationTestImplementation"("org.assertj:assertj-core:3.27.7")
    "integrationTestRuntimeOnly"("com.h2database:h2")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests with real QEMU VMs"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "15m")
}
