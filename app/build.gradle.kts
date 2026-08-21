import groovy.json.JsonSlurper
import org.gradle.api.tasks.compile.JavaCompile
import java.io.FileInputStream
import java.math.BigDecimal
import java.net.URI
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.util.Locale

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

android {
    namespace = "com.ez.zalopatch"
    compileSdk = 34
    useLibrary("android.test.base")
    useLibrary("android.test.runner")

    defaultConfig {
        applicationId = "com.ez.zalopatch"
        minSdk = 24
        targetSdk = 34
        versionCode = 163
        versionName = "0.4.159-status-privacy+B163-20260820T1716Z"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        buildConfigField("String", "DIAGNOSTIC_INTAKE_URL", "\"$diagnosticIntakeUri\"")
        buildConfigField("String", "SYMBOL_CATALOG_URL", "\"$symbolCatalogUri\"")
        testInstrumentationRunnerArguments["class"] = listOf(
            "com.ez.zalopatch.SettingsBackupTest",
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
            java.srcDir(layout.buildDirectory.dir("generated/source/symbolSchema/main"))
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

        val escaped = json
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n\" +\n        \"")
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
                    return "$escaped";
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

    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
