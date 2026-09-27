// The seam between Myau+'s shared code and a particular Minecraft version.
//
// Nothing here may reference Minecraft: this module is compiled without it on the classpath,
// which is what stops version-specific types leaking into `core`.
plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}
