buildscript {

    extra["minSdkVersion"] = 21
    extra["compileSdkVersion"] = 30
    extra["targetSdkVersion"] = 30

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.0")
        classpath("com.android.tools.build:gradle:7.4.0")
//        classpath("org.jetbrains.dokka:dokka-gradle-plugin:1.4.30") // publisher uses 1.4.20 which goes OOM
//        classpath("io.deepmedia.tools:publisher:0.5.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean", Delete::class) {
    delete(buildDir)
}
