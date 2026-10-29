import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signature de release : fournie par le CI (secrets GitHub). Elle ne doit jamais changer,
// sinon Android refuse d'installer la mise à jour par-dessus l'app existante.
val keystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.icerayzer.moneytracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.icerayzer.moneytracker" // ne jamais changer : les données sont liées à cet identifiant
        minSdk = 26
        targetSdk = 36
        // Le CI passe le numéro de build : il augmente à chaque compilation, donc à chaque release.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "dev"
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // ponytail: app minuscule, pas de R8 ; à activer (avec règles @JavascriptInterface) si la taille compte
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
