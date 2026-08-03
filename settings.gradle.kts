rootProject.name = "orchard"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "core",
    "roots",
    "harvest",
    "nursery",
    "greenhouse",
    "apiary",
    "trellis",
    "trowel",
    "fence",
    "gateway",
    "integration-tests"
)
