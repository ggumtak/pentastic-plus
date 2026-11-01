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
        // Maven Central이 일시적으로 응답하지 않을 때를 대비한 구글 미러
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "pentastic-plus"
include(":app")
