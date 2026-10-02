import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    System.getProperty("hy.bingHtml")?.let { systemProperty("hy.bingHtml", it) }
    System.getProperty("hy.dumpGrammars")?.let { systemProperty("hy.dumpGrammars", it) }
    System.getProperty("hy.docxOut")?.let { systemProperty("hy.docxOut", it) }
    System.getProperty("hy.evalOut")?.let { systemProperty("hy.evalOut", it) }
    testLogging { showStandardStreams = true }
}
