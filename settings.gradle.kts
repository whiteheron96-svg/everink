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
        // MuPDF (Artifex) 공식 아티팩트: com.artifex.mupdf:fitz (AGPL)
        maven { url = uri("https://maven.ghostscript.com") }
    }
}

rootProject.name = "EverInk"
include(":app")
