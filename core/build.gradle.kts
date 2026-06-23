plugins {
    `java-library`
}

dependencies {
    // Annotations only (Jackson type-info / creator metadata on the model). This is the
    // dependency-free annotations jar — NOT databind — so core stays light: the actual
    // ObjectMapper and (de)serialization logic live in :harvest.
    api("com.fasterxml.jackson.core:jackson-annotations")
}
