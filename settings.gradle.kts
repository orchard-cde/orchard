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
    "moderne",
    "api",
    "server",
    "trowel",
    "canopy"
)
