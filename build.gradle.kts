import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("com.android.library") version "8.13.2" apply false
    id("com.vanniktech.maven.publish") version "0.33.0" apply false
}

allprojects {
    group = "io.revnix"
    version = "1.5.0"
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            coordinates("io.revnix", project.name, project.version.toString())
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set("https://github.com/Oth-tech/RevnixSDK-Android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("revnix")
                        name.set("Revnix")
                        url.set("https://revnix.io")
                    }
                }
                scm {
                    url.set("https://github.com/Oth-tech/RevnixSDK-Android")
                    connection.set("scm:git:https://github.com/Oth-tech/RevnixSDK-Android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Oth-tech/RevnixSDK-Android.git")
                }
            }
        }
    }
}
