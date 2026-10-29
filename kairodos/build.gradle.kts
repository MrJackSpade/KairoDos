plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

extra["kairoProduct"] = "kairodos"
extra["kairoLabel"] = "KairoDos"
extra["kairoLauncherBackground"] = "#00110A"
extra["kairoCatalogRules"] = "com.mrjackspade.kairodos.DosCatalog"
apply(from = rootProject.file("shared/gradle/kairo-app.gradle"))

// DOSBox Staging's runtime resources, beside the shared catalog assets.
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/staging-assets"))

val stagingResources by tasks.registering(Sync::class) {
    from(rootProject.file("third_party/dosbox-staging/resources"))
    into(layout.buildDirectory.dir("generated/staging-assets/staging-resources"))
    exclude("meson.build")
}
tasks.named("preBuild") { dependsOn(stagingResources) }

dependencies {
    implementation(project(":backend-dos"))
    // Game ZIPs read in place through their document's descriptor (DosZip.members).
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}
