plugins {
    java
}

group = "barry"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Emit class-file version 21 so the agent loads on MC's Java 21 runtime.
    // The build JDK is 25 but the payload must be compatible with the target.
    options.release.set(21)
    options.compilerArgs.addAll(listOf(
        "--add-modules", "jdk.attach",
        "-Xlint:none"
    ))
}

tasks.jar {
    archiveBaseName = "barry"
    manifest {
        attributes(
            "Agent-Class" to "barry.Bootstrap",
            "Premain-Class" to "barry.Bootstrap",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            "Main-Class" to "barry.launch.Main"
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

sourceSets {
    main {
        java {
            srcDir("src/main/java")
        }
        resources {
            srcDir("src/main/resources")
        }
    }
}
