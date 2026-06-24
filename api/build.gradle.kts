plugins {
    `java-library`
    id("me.champeau.jmh")
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

jmh {
    jmhVersion.set("1.37")
    fork.set(1)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json").get().asFile)
}
