plugins {
    id("org.springframework.boot")
}

springBoot {
    buildInfo()
}

dependencies {
    implementation(project(":core"))
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.springframework.boot:spring-boot-restclient")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
