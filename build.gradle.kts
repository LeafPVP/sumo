plugins {
    java
    id("com.diffplug.spotless") version "8.10.2"
}

group = "dev.giqnt.leafpvp.sumo"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.98.0")
        formatAnnotations()
        removeUnusedImports()
        endWithNewline()
    }
}

repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("dev.giqnt.leafpvp:leafpvp-duel-api:1.0.0-SNAPSHOT")
}
