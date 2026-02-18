plugins {
    id("org.springframework.boot")
    id("com.vaadin") version "24.6.3"
}

val vaadinVersion = "24.6.3"

dependencies {
    implementation(project(":core"))
    implementation(project(":roots"))
    implementation(project(":nursery"))
    implementation(project(":api"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("com.vaadin:vaadin-spring-boot-starter:$vaadinVersion")

    implementation("org.postgresql:postgresql:42.7.4")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
}

dependencyManagement {
    imports {
        mavenBom("com.vaadin:vaadin-bom:$vaadinVersion")
    }
}

vaadin {
    productionMode = false
}
