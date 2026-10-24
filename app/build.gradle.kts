import groovy.json.JsonSlurper
import org.gradle.api.tasks.compile.JavaCompile
import java.io.FileInputStream
import java.math.BigDecimal
import java.net.URI
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
}

val diagnosticIntakeUrl = providers.gradleProperty("diagnosticIntakeUrl")
    .orElse("https://reports.eza.dpdns.org/v1/reports")
val diagnosticIntakeUri = URI(diagnosticIntakeUrl.get())
require(
    diagnosticIntakeUri.scheme == "https" &&
        !diagnosticIntakeUri.host.isNullOrBlank() &&
        diagnosticIntakeUri.userInfo == null &&
        diagnosticIntakeUri.query == null &&
        diagnosticIntakeUri.fragment == null &&
        diagnosticIntakeUri.path == "/v1/reports"
) {
    "diagnosticIntakeUrl must be an HTTPS /v1/reports URL without credentials or query data"
}
val symbolCatalogUrl = providers.gradleProperty("symbolCatalogUrl")
    .orElse("https://catalog.eza.dpdns.org/v1/resolve")
val symbolCatalogUri = URI(symbolCatalogUrl.get())
require(
    symbolCatalogUri.scheme == "https" &&
        !symbolCatalogUri.host.isNullOrBlank() &&
        symbolCatalogUri.userInfo == null &&
        symbolCatalogUri.query == null &&
        symbolCatalogUri.fragment == null &&
        symbolCatalogUri.path == "/v1/resolve"
) {
    "symbolCatalogUrl must be an HTTPS /v1/resolve URL without credentials or query data"
}

val signingKeystoreFile = providers.environmentVariable("SIGNING_KEYSTORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
var releaseSigningError = "release signing credentials are missing"
val releaseKeystoreValid = if (
    listOf(
        signingKeystoreFile,
        signingStorePassword,
        signingKeyAlias,
        signingKeyPassword
    ).all { !it.isNullOrBlank() }
) {
    try {
        val keyStore = KeyStore.getInstance("JKS")
        FileInputStream(requireNotNull(signingKeystoreFile)).use {
            keyStore.load(it, requireNotNull(signingStorePassword).toCharArray())
        }
        val key = keyStore.getKey(
            requireNotNull(signingKeyAlias),
            requireNotNull(signingKeyPassword).toCharArray()
        )
        if (key !is PrivateKey || keyStore.getCertificate(signingKeyAlias) == null) {
            throw KeyStoreException("alias does not contain a private key and certificate")
        }
        releaseSigningError = ""
        true
    } catch (error: Throwable) {
        releaseSigningError =
            "release signing credentials are unusable (${error.javaClass.simpleName})"
        false
    }
} else {
    false
}

gradle.taskGraph.whenReady(
    object : org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
        override fun execute(graph: org.gradle.api.execution.TaskExecutionGraph) {
            val packagesRelease = graph.allTasks.any { task ->
                task.project == project && task.name.lowercase(Locale.ROOT)
                    .matches(Regex("(assemble|package|bundle).*release.*"))
            }
            if (packagesRelease && !releaseKeystoreValid) {
                throw GradleException(
                    "Zalo Patch: $releaseSigningError; refusing to create release artifacts."
                )
            }
        }
    }
)

val lspatch = providers.gradleProperty("lspatch").map { it.toBooleanStrict() }.orElse(false).get()

android {
    namespace = "com.ez.zalopatch"
    // Service 102 requires Android 37 headers; runtime minimum and target stay unchanged.
    compileSdk = 37
    useLibrary("android.test.base")
    useLibrary("android.test.runner")

    defaultConfig {
        applicationId = "com.ez.zalopatch"
        buildConfigField("boolean", "LSPATCH", lspatch.toString())
        // LibXposed API 102 declares API 26 as its minimum; the API 24/25 range
        // supported by the legacy API 82 entry ends with this migration.
        minSdk = 26
        targetSdk = 34
        versionCode = 346
        versionName = "0.4.342"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        buildConfigField("String", "DIAGNOSTIC_INTAKE_URL", "\"$diagnosticIntakeUri\"")
        buildConfigField("String", "SYMBOL_CATALOG_URL", "\"$symbolCatalogUri\"")
        testInstrumentationRunnerArguments["class"] = listOf(
            "com.ez.zalopatch.SettingsBackupTest",
            "com.ez.zalopatch.FrameworkConfigTest",
            "com.ez.zalopatch.NotificationHistoryStoreTest",
            "com.ez.zalopatch.CallRecordingTranscoderTest",
            "com.ez.zalopatch.SymbolSchemaProfileTest",
            "com.ez.zalopatch.DiagnosticReportingTest",
            "com.ez.zalopatch.SettingsUiSmokeTest",
            "com.ez.zalopatch.TweakHookInfoCoverageTest"
        ).joinToString(",")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystoreValid) {
            create("release") {
                storeFile = file(requireNotNull(signingKeystoreFile))
                storePassword = requireNotNull(signingStorePassword)
                keyAlias = requireNotNull(signingKeyAlias)
                keyPassword = requireNotNull(signingKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // values-vi intentionally translates only ordinary user-facing copy; developer and
        // diagnostic surfaces fall back to English by contract.
        disable += "MissingTranslation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/source/symbolSchema/main").get().asFile)
            java.srcDir(if (lspatch) "src/lspatch/java" else "src/modern/java")
            resources.srcDir(if (lspatch) "src/lspatch/resources" else "src/modern/resources")
            assets.srcDir(if (lspatch) "src/lspatch/assets" else "src/modern/assets")
            manifest.srcFile(if (lspatch) "src/lspatch/AndroidManifest.xml" else "src/main/AndroidManifest.xml")
        }
    }
}

val generateBundledSymbolSchema by tasks.registering {
    val input = layout.projectDirectory.file("src/main/assets/symbol-schema.json")
    val output = layout.buildDirectory.file(
        "generated/source/symbolSchema/main/com/ez/zalopatch/BundledSymbolSchemaJson.java"
    )
    inputs.file(input)
    outputs.file(output)

    doLast {
        val json = input.asFile.readText()
        fun fail(constraint: String): Nothing =
            throw GradleException("symbol-schema.json validation failed: $constraint")
        fun objectAt(value: Any?, path: String): Map<*, *> =
            value as? Map<*, *> ?: fail("$path must be an object")
        fun isOne(value: Any?): Boolean =
            value is Number && value.toString().toBigDecimal().compareTo(BigDecimal.ONE) == 0

        val bundle = try {
            objectAt(JsonSlurper().parseText(json), "top level")
        } catch (error: GradleException) {
            throw error
        } catch (error: Exception) {
            fail("invalid JSON (${error.message ?: error.javaClass.simpleName})")
        }

        if (!isOne(bundle["bundle_version"])) {
            fail("top-level bundle_version must equal 1")
        }
        if (bundle["zalo_package"] != "com.zing.zalo") {
            fail("top-level zalo_package must equal com.zing.zalo")
        }
        val profiles = bundle["profiles"] as? List<*>
            ?: fail("top-level profiles must be an array")
        if (profiles.isEmpty()) {
            fail("top-level profiles must be non-empty")
        }

        val sha256 = Regex("^[0-9a-f]{64}$")
        val allowedVerification = setOf(
            "static-verified",
            "instrumented",
            "device-smoke-tested",
            "device-verified"
        )
        val versionProfiles = mutableMapOf<BigDecimal, Int>()
        profiles.forEachIndexed { index, value ->
            val profile = objectAt(value, "profile[$index]")
            if (!isOne(profile["schema_version"])) {
                fail("profile[$index].schema_version must equal 1")
            }
            if (profile["schema_revision"] !is Number) {
                fail("profile[$index].schema_revision must be a number")
            }
            if (profile["zalo_package"] != "com.zing.zalo") {
                fail("profile[$index].zalo_package must equal com.zing.zalo")
            }

            val zaloVersion = objectAt(profile["zalo_version"], "profile[$index].zalo_version")
            val minCode = zaloVersion["min_code"] as? Number
                ?: fail("profile[$index].zalo_version.min_code must be a number")
            val maxCode = zaloVersion["max_code"] as? Number
                ?: fail("profile[$index].zalo_version.max_code must be a number")
            val numericMinCode = minCode.toString().toBigDecimal()
            val numericMaxCode = maxCode.toString().toBigDecimal()
            if (numericMinCode.signum() <= 0) {
                fail("profile[$index].zalo_version.min_code must be greater than 0 (was $minCode)")
            }
            if (numericMinCode.compareTo(numericMaxCode) != 0) {
                fail("profile[$index].zalo_version min_code must equal max_code (was $minCode and $maxCode)")
            }
            val previousIndex = versionProfiles.put(numericMinCode, index)
            if (previousIndex != null) {
                fail("zalo_version.min_code $minCode is duplicated in profiles[$previousIndex] and [$index]")
            }

            val artifact = objectAt(profile["artifact"], "profile[$index].artifact")
            val baseSha256 = artifact["base_apk_sha256"] as? String
            if (baseSha256 == null || !sha256.matches(baseSha256)) {
                fail("profile[$index].artifact.base_apk_sha256 must match ^[0-9a-f]{64}$")
            }
            val signerSha256 = artifact["signer_sha256"] as? String
            if (signerSha256 == null || !sha256.matches(signerSha256)) {
                fail("profile[$index].artifact.signer_sha256 must match ^[0-9a-f]{64}$")
            }
            if (artifact["hook_code_apk"] != "base") {
                fail("profile[$index].artifact.hook_code_apk must equal base")
            }
            val verification = artifact["verification"] as? String
            if (verification !in allowedVerification) {
                fail("profile[$index].artifact.verification must be one of ${allowedVerification.joinToString()}")
            }

            val symbols = objectAt(profile["symbols"], "profile[$index].symbols")
            val bottomTabs = objectAt(symbols["bottom_tabs"], "profile[$index].symbols.bottom_tabs")
            objectAt(bottomTabs["current_methods"], "profile[$index].symbols.bottom_tabs.current_methods")
        }

        // Bound each Java constant below the modified UTF-8 limit and prevent folding.
        val escapedChunks = json.chunked(16_000).joinToString(",\n        ") { chunk ->
            "\"" + chunk
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n\" +\n        \"") + "\""
        }
        val outputFile = output.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package com.ez.zalopatch;

            // Generated from src/main/assets/symbol-schema.json. Do not edit.
            final class BundledSymbolSchemaJson {
                private BundledSymbolSchemaJson() {
                }

                static String json() {
                    return String.join("", $escapedChunks);
                }
            }
            """.trimIndent()
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateBundledSymbolSchema)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    // DexKit pilot (Zinstant ad-bind resolution only): upstream LuckyPray/DexKit 2.2.0,
    // Maven Central org.luckypray:dexkit:2.2.0 (AAR, 2026-04-11). Kotlin bindings are
    // Apache-2.0; native Core/ is LGPL-3.0 and ships unmodified inside this APK, loaded
    // via System.loadLibrary (dynamic link). Notices live in assets/dexkit-notices.txt.
    // Provenance and pilot scope: recorded in the module project's internal documentation.
    implementation("org.luckypray:dexkit:2.2.0")

    if (lspatch) {
        compileOnly("de.robv.android.xposed:api:82")
        testRuntimeOnly("de.robv.android.xposed:api:82")
    } else {
        compileOnly("io.github.libxposed:api:102.0.0")
        testRuntimeOnly("io.github.libxposed:api:102.0.0")
    }
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Check the actual artifact consumed by the loader, rather than only source metadata.
tasks.register("verifyFrameworkArtifact") {
    val artifactPath = providers.gradleProperty("frameworkArtifact")
    if (!artifactPath.isPresent) dependsOn("assembleDebug")
    doLast {
        val apk = artifactPath.orNull?.let { file(it) }
            ?: layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        ZipFile(apk).use { zip ->
            fun content(name: String): String? = zip.getEntry(name)?.let {
                zip.getInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
            }
            val legacyEntry = "com.ez.zalopatch.ModuleEntry"
            val modernEntry = "com.ez.zalopatch.ZaloPatchModule"
            val entry = if (lspatch) legacyEntry else modernEntry
            val init = if (lspatch) "assets/xposed_init" else "META-INF/xposed/java_init.list"
            check(content(init)?.trim() == entry) { "Wrong loader entry for $init" }
            val other = if (lspatch) "META-INF/xposed/java_init.list" else "assets/xposed_init"
            check(content(other) == null) { "Opposing loader contract present: $other" }
            if (!lspatch) {
                check(content("META-INF/xposed/scope.list")?.trim() == "com.zing.zalo")
                val properties = Properties().apply {
                    load(content("META-INF/xposed/module.prop")!!.reader())
                }
                check(properties.getProperty("minApiVersion") == "102")
                check(properties.getProperty("targetApiVersion") == "102")
            }
            val dex = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes([0-9]+)?\\.dex")) }
                .map { zip.getInputStream(it).use { stream -> stream.readBytes().toString(Charsets.ISO_8859_1) } }
                .toList().joinToString("")
            check(dex.contains("L" + entry.replace('.', '/') + ";")) { "Loader class missing" }
            val forbidden = if (lspatch) "Lio/github/libxposed/api/" else "Lde/robv/android/xposed/"
            check(!dex.contains(forbidden)) { "Opposing API linked or bundled: $forbidden" }
            check(!dex.contains("L" + (if (lspatch) modernEntry else legacyEntry).replace('.', '/') + ";"))
        }
        val contract = if (lspatch) "lspatch" else "modern"
        val named = layout.buildDirectory.file("verified/$contract/zalo-patch-$contract-${apk.name.removePrefix("app-")}").get().asFile
        named.parentFile.mkdirs()
        apk.copyTo(named, overwrite = true)
        println("Verified $contract loader artifact: $named")
    }
}
