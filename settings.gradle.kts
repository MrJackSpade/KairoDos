apply(from = "shared/gradle/android-settings.gradle")

rootProject.name = "KairoDos"
include(":kairodos", ":backend-dos", ":frontend")
project(":frontend").projectDir = file("shared/frontend")
