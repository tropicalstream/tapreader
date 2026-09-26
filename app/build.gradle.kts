plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tapreader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tapreader.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Pure-JVM tests of the casting/segmenting/alignment core.
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("tapreader.root", rootDir.absolutePath)
            // ./gradlew testDebugUnitTest -Plive=1 runs the live fish/Gemini tests too.
            it.systemProperty("tapreader.live", (project.findProperty("live") ?: "").toString())
            it.systemProperty("tapreader.tune", (project.findProperty("tune") ?: "").toString())
            it.systemProperty("tapreader.books", (project.findProperty("books") ?: "").toString())
            it.outputs.upToDateWhen { false }
            it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }

    buildFeatures { buildConfig = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/INDEX.LIST", "META-INF/versions/**"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")

    // fish.audio TTS + Gutendex / OPDS downloads.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // PDF text extraction (PdfBox Android port). Page images for covers come
    // from the platform PdfRenderer. NAS browsing (smbj) — the glasses do the
    // SMB2/3 work, driven by the web companion. Both pull bouncycastle at
    // different versions, so exclude it from both and pin one modern copy.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    implementation("com.hierynomus:smbj:0.13.0") {
        exclude(group = "org.bouncycastle")
    }
    // srvsvc RPC over smbj — lets the glasses enumerate a NAS's shares so the
    // companion can browse from the root instead of asking for a share name.
    implementation("com.rapid7.client:dcerpc:0.12.1") {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.hierynomus", module = "smbj")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-nop:2.0.9")


    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; tests need the real thing.
    testImplementation("org.json:json:20240303")
}
