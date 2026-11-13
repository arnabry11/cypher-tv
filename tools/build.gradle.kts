plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("tv.cypher.tools.MainKt") }
tasks.test { useJUnitPlatform() }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
