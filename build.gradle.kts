import org.gradle.jvm.tasks.Jar

buildscript {
    System.setProperty("kotlinVersion", "2.3.10")
    System.setProperty("vertxVersion", "5.0.8")
}

group = "com.literp"
version = "0.0.1"

val useJava = 25
val vertxVersion: String by System.getProperties()
val appReleaseName = "${rootProject.name}-${version}"

plugins {
    val kotlinVersion: String by System.getProperties()

    kotlin("jvm").version(kotlinVersion)
    id("java")
    id("org.graalvm.buildtools.native").version("0.11.3")
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Vertx Core
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")

    // Vertx Codegen and Proxy
    implementation("io.vertx:vertx-service-proxy:$vertxVersion")
    compileOnly("io.vertx:vertx-codegen:$vertxVersion")
    annotationProcessor("io.vertx:vertx-codegen:$vertxVersion:processor")
    annotationProcessor("io.vertx:vertx-service-proxy:$vertxVersion")

    // Vertx Web
    implementation("io.vertx:vertx-web:$vertxVersion")

    // Vertx OpenAPI
    implementation("io.vertx:vertx-openapi:${vertxVersion}")
    implementation("io.vertx:vertx-web-openapi-router:${vertxVersion}")

    // Vertx Rxjava
    implementation("io.vertx:vertx-rx-java3:$vertxVersion")
    implementation("io.vertx:vertx-rx-java3-gen:$vertxVersion")

    // Vertx Postgresql
    implementation("io.vertx:vertx-pg-client:$vertxVersion")
    // Scram SASL SCRAM-SHA-256
    implementation("com.ongres.scram:scram-client:3.2")
}

val generatedBuildDirectory = layout.buildDirectory.dir("generated/sources/java")
val generatedJavaPath: String = generatedBuildDirectory.get().asFile.path

sourceSets {
    main {
        java {
            srcDirs(srcDirs, generatedJavaPath)
        }
    }
}

tasks.test {
    // useJUnitPlatform()
}

tasks.register<Jar>("kotlinJar") {
    group = "build"
    description = "Kotlin jar"

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Implementation-Title"] = rootProject.name
        attributes["Implementation-Version"] = archiveVersion
        attributes["Main-Class"] = "com.literp.AppKt"
    }

    val sourcesMain = sourceSets.main.get()
    val contents = configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } + sourcesMain.output
    from(contents)

    dependsOn(tasks.classes)
}

tasks.register<Copy>("copyKotlinJar") {
    group = "build"
    description = "Copy jar"

    val jarPath = layout.buildDirectory.file("libs/$appReleaseName.jar").get().asFile.path
    from(jarPath)
    into(layout.buildDirectory.dir("libs").get().asFile.path)
    rename("${appReleaseName}.jar", "${rootProject.name}.jar")

    dependsOn(tasks["kotlinJar"])
}

tasks.register<JavaExec>("runJar") {
    group = "build"
    description = "Run the jar file"

    classpath("${layout.buildDirectory.get().asFile.path}/libs/$appReleaseName.jar")
    dependsOn(tasks["kotlinJar"])
}

tasks.register<JavaCompile>("annotationProcessing") {
    group = "build"
    description = "Generate vertx codegen"
    source = sourceSets.main.get().java

    classpath = sourceSets.main.get().compileClasspath

    options.annotationProcessorPath = configurations.annotationProcessor.get()
    options.debugOptions.debugLevel = "source,lines,vars"
    options.compilerArgs = listOf(
        "-proc:only",
        "-processor", "io.vertx.codegen.CodeGenProcessor"
    )
    destinationDirectory = generatedBuildDirectory
}

tasks.register<Copy>("copyNativeCompile") {
    group = "build"
    description = "Copy native image into project root directory"

    val nativeImagePath = layout.buildDirectory.file("native/nativeCompile/${appReleaseName}.run").get().asFile.path
    from(nativeImagePath)
    into(projectDir.absolutePath)
    rename("${appReleaseName}.run", "${rootProject.name}.release.run")
}

tasks.register<Exec>("migrateDatabase") {
    group = "build"
    description = "Run migrate database"

    commandLine("bash", "-c", "echo \"Running migrate database\"")
}

tasks.compileKotlin {

}

tasks.compileJava {

}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(useJava))
    }
}

kotlin {
    jvmToolchain(useJava)
}

graalvmNative {
    binaries {
        named("main") {
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(useJava))
            })
            imageName.set("${appReleaseName}.run")
            mainClass.set("com.literp.AppKt")
            fallback.set(false)
            verbose.set(true)
            sharedLibrary.set(false)

            val path = "${projectDir}/src/main/resources/META-INF/native-image"
            buildArgs.add("-H:ReflectionConfigurationFiles=${path}/reflect-config.json")
            buildArgs.add("-H:EnableURLProtocols=http,https")
            buildArgs.add("-H:+InstallExitHandlers")
            buildArgs.add("-H:+ReportUnsupportedElementsAtRuntime")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("--enable-http")
            buildArgs.add("--enable-https")
            buildArgs.add("--allow-incomplete-classpath")
            buildArgs.add("--report-unsupported-elements-at-runtime")
        }
    }
}
