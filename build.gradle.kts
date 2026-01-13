allprojects {
    group = "dev.luna5ama"
    version = "0.0.1-SNAPSHOT"
}

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.jarOptimizer)
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.lwjgl", "lwjgl-stb")
    implementation("org.lwjgl", "lwjgl-nfd")
    val platforms = listOf("linux", "windows")
    platforms.forEach {
        runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-$it")
        runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-$it")
        runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = "natives-$it")
        runtimeOnly("org.lwjgl", "lwjgl-stb", classifier = "natives-$it")
        runtimeOnly("org.lwjgl", "lwjgl-nfd", classifier = "natives-$it")
    }
}



tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "dev.luna5ama.nvasmdump.Main"
        }
    }

    val fatJar by registering(Jar::class) {
        group = "build"

        from(jar.get().archiveFile.map { zipTree(it) })
        from(configurations.runtimeClasspath.get().elements.map { set ->
            set.map {
                if (it.asFile.isDirectory) it else zipTree(
                    it
                )
            }
        })

        manifest {
            attributes["Main-Class"] = "dev.luna5ama.nvasmdump.Main"
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        archiveClassifier.set("fatjar")
    }

    val optimizeFatJar = jarOptimizer.register(
        fatJar,
        "dev.luna5ama.nvasmdump", "org.lwjgl"
    )

    artifacts {
        archives(optimizeFatJar)
    }
}