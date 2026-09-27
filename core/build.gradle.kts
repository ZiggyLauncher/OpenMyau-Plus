// Myau+'s version-independent code: modules, GUIs, config, events.
//
// Compiled without Minecraft on the classpath. Anything that needs the game goes through an
// interface in :platform-api, implemented by the per-version module that hosts us.
plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":platform-api"))

    // Gson is not Minecraft: every version Myau+ targets already ships it, so it is compiled
    // against but never bundled. 2.2.4 is what 1.8.9 carries, i.e. the oldest API we must fit.
    compileOnly("com.google.code.gson:gson:2.2.4")
}

tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}
