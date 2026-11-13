pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GestioneSpese"

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:designsystem")
include(":domain")
include(":data")
