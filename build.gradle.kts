buildscript {

    extra["minSdkVersion"] = 21
    extra["compileSdkVersion"] = 30
    extra["targetSdkVersion"] = 30

    repositories {
        google()
        mavenCentral()
        jcenter()
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.20")
        classpath("com.android.tools.build:gradle:7.3.1")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:1.4.30") // publisher uses 1.4.20 which goes OOM
        classpath("io.deepmedia.tools:publisher:0.5.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        jcenter()
    }
}

tasks.register("clean", Delete::class) {
    delete(buildDir)
}
