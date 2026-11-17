plugins {
    java
}

group = "barry"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
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
