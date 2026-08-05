plugins {
    id("org.springframework.boot")
}

springBoot {
    buildInfo()
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-restclient")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("net.i2p.crypto:eddsa:0.3.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
