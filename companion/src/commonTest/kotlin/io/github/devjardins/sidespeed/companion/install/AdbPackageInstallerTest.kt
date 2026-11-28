package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.InstallStream
import io.github.devjardins.sidespeed.companion.tv.TlsNotNegotiated
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvSession
import io.github.devjardins.sidespeed.companion.tv.TvSessions
import io.github.devjardins.sidespeed.companion.tv.UnsupportedInstallStreamException
import io.github.devjardins.sidespeed.companion.tv.VisitResult
import io.github.devjardins.sidespeed.helper.SideSpeedPackages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbPackageInstallerTest {

    private class RecordingInstallStream(
        private val reply: String,
        private val failOnWriteAfterBytes: Int? = null,
        private val onFirstWrite: (() -> Unit)? = null,
        private val blockWriteUntilClosed: Boolean = false,
    ) : InstallStream {
        val written = ByteArrayOutputStream()
        var flushed = false
            private set
        var closed = false
            private set
        private var notifiedFirstWrite = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (closed) throw IOException("stream closed")
            if (!notifiedFirstWrite) {
                notifiedFirstWrite = true
                onFirstWrite?.invoke()
            }
            if (blockWriteUntilClosed) {
                while (!closed) {
                    Thread.sleep(5)
                }
                throw IOException("stream closed during write")
            }
            failOnWriteAfterBytes?.let { limit ->
                if (written.size() + length > limit) {
                    throw IOException("broken pipe")
                }
            }
            written.write(bytes, offset, length)
        }

        override fun flush() {
            if (closed) throw IOException("stream closed")
            flushed = true
        }

        override fun readAllText(): String {
            if (closed) throw IOException("stream closed")
            return reply
        }

        override fun close() {
            closed = true
        }
    }

    private fun singleMeta(pkg: String, versionCode: Long = 100L, sizeBytes: Long = 16L) = ApkEntryMeta(
        zipEntryName = null,
        sizeBytes = sizeBytes,
        manifest = ApkManifestInfo(
            packageName = pkg,
            versionCode = versionCode,
            versionName = "1.0.0",
            splitName = null,
            configForSplit = null,
            isFeatureSplit = false,
            literalLabel = "Example",
        ),
    )

    private fun singlePkg(pkg: String = "com.example.app", versionCode: Long = 100L, bytes: ByteArray): Pair<InspectedPackage, SplitSelection> {
        val meta = singleMeta(pkg, versionCode, bytes.size.toLong())
        val inspected = InspectedPackage(
            packageName = pkg,
            displayLabel = "Example",
            versionCode = versionCode,
            versionName = "1.0.0",
            isBundle = false,
            base = meta,
            splits = emptyList(),
        )
        val selection = SplitSelection(
            splits = listOf(SelectedSplit(meta, "base.apk", null)),
            totalBytes = bytes.size.toLong(),
            summaryTags = emptyList(),
        )
        return inspected to selection
    }

    private fun preflightOutputs(
        pkg: String = "com.example.app",
        abiList: String = "arm64-v8a,armeabi-v7a",
        abiFallback: String = "",
        density: String = "320",
        locale: String = "en-US",
        localeFallback: String = "",
        pmPath: String = "",
        dump: String = "",
    ): Map<String, String> = buildMap {
        put(TvCommands.PROP_ABILIST, abiList)
        if (abiList.isBlank()) put(TvCommands.PROP_ABI, abiFallback)
        put(TvCommands.PROP_DENSITY, density)
        put(TvCommands.PROP_LOCALE, locale)
        if (locale.isBlank()) put(TvCommands.PROP_LOCALE_FALLBACK, localeFallback)
        put(TvCommands.packagePath(pkg), pmPath)
        if (pmPath.isNotBlank()) put(TvCommands.packageDump(pkg), dump)
    }

    @Test
    fun preflight_detects_fresh_sideloaded_store_system_and_blocks_downgrade() = runTest {
        val installer = AdbPackageInstaller()
        val bytes = ByteArray(64) { it.toByte() }
        val (pkg, _) = singlePkg(versionCode = 120L, bytes = bytes)

        // Fresh install (also tests fallback ABI and locale props)
        val freshSession = FakeTvSession(
            preflightOutputs(
                abiList = "",
                abiFallback = "armeabi-v7a",
                locale = "",
                localeFallback = "fr-FR",
                pmPath = "",
            ),
        )
        val fresh = installer.preflight(freshSession, pkg).getOrThrow()
        assertFalse(fresh.isInstalled)
        assertFalse(fresh.isStoreInstalled)
        assertFalse(fresh.isSystemApp)
        assertNull(fresh.installedVersionName)
        assertEquals(listOf("armeabi-v7a"), fresh.device.abiList)
        assertEquals(320, fresh.device.densityDpi)
        assertEquals("fr-FR", fresh.device.localeTag)

        // Sideloaded app
        val sideloadDump = """
            Packages:
              Package [com.example.app] (11):
                versionCode=100 minSdk=26
                versionName=1.0.0
                pkgFlags=[ HAS_CODE ]
                installerPackageName=null
        """.trimIndent()
        val sideSession = FakeTvSession(preflightOutputs(pmPath = "package:/data/app/x/base.apk", dump = sideloadDump))
        val side = installer.preflight(sideSession, pkg).getOrThrow()
        assertTrue(side.isInstalled)
        assertEquals("1.0.0", side.installedVersionName)
        assertFalse(side.isStoreInstalled)
        assertFalse(side.isSystemApp)

        // Store-installed app (Google Play)
        val playDump = """
            Packages:
              Package [com.example.app] (11):
                versionCode=100 minSdk=26
                versionName=1.0.0
                pkgFlags=[ HAS_CODE ]
                installerPackageName=com.android.vending
        """.trimIndent()
        val playSession = FakeTvSession(preflightOutputs(pmPath = "package:/data/app/x/base.apk", dump = playDump))
        val play = installer.preflight(playSession, pkg).getOrThrow()
        assertTrue(play.isInstalled)
        assertTrue(play.isStoreInstalled)
        assertEquals("com.android.vending", play.installerPackage)
        assertFalse(play.isSystemApp)

        // System app
        val systemDump = """
            Packages:
              Package [com.example.app] (11):
                versionCode=100 minSdk=26
                versionName=1.0.0
                pkgFlags=[ SYSTEM HAS_CODE ]
                installerPackageName=null
        """.trimIndent()
        val systemSession = FakeTvSession(preflightOutputs(pmPath = "package:/system/app/x/x.apk", dump = systemDump))
        val sys = installer.preflight(systemSession, pkg).getOrThrow()
        assertTrue(sys.isInstalled)
        assertTrue(sys.isSystemApp)
        assertFalse(sys.isStoreInstalled)

        // Downgrade blocked
        val newerDump = """
            Packages:
              Package [com.example.app] (11):
                versionCode=200 minSdk=26
                versionName=2.0.0
                pkgFlags=[ HAS_CODE ]
        """.trimIndent()
        val downgradeSession = FakeTvSession(preflightOutputs(pmPath = "package:/data/app/x/base.apk", dump = newerDump))
        val downgrade = installer.preflight(downgradeSession, pkg)
        assertTrue(downgrade.isFailure)
        assertEquals(InstallError.DowngradeNotAllowed("2.0.0"), downgrade.installErrorOrNull())

        // Incompatible ABI bundle blocked
        val baseMeta = ApkEntryMeta("base.apk", 100L, ApkManifestInfo("com.example.app", 120L, "1.2", null, null, false, "Ex"))
        val x86Meta = ApkEntryMeta("config.x86_64.apk", 100L, ApkManifestInfo("com.example.app", 120L, "1.2", "config.x86_64", null, false, null))
        val armSession = FakeTvSession(preflightOutputs(abiList = "arm64-v8a", pmPath = ""))
        val bundle = InspectedPackage("com.example.app", "Ex", 120L, "1.2", true, baseMeta, listOf(x86Meta))
        val abiFail = installer.preflight(armSession, bundle)
        assertEquals(InstallError.IncompatibleAbi, abiFail.installErrorOrNull())
    }

    @Test
    fun preflight_selects_bundletool_variant_matching_tv_sdk() = runTest {
        val installer = AdbPackageInstaller()
        InstallTestFixtures.TempZipInstallSource.fromBytes(
            "multi.apks",
            InstallTestFixtures.buildMultiVariantApks(),
        ).use { source ->
            val pkg = ApkInspector.inspect(source).getOrThrow()

            val sdk28Session = FakeTvSession(preflightOutputs(pkg = "com.example.multi") + (TvCommands.PROP_SDK to "28"))
            val pre28 = installer.preflight(sdk28Session, pkg).getOrThrow()
            assertTrue(pre28.selection.splits.map { it.entry.zipEntryName }.contains("splits/base-master_2.apk"))
            assertTrue(pre28.selection.splits.all { it.entry.zipEntryName!!.endsWith("_2.apk") })
            assertNull(pre28.pkg.variants)
            assertEquals("splits/base-master_2.apk", pre28.pkg.base.zipEntryName)

            val sdk34Session = FakeTvSession(preflightOutputs(pkg = "com.example.multi") + (TvCommands.PROP_SDK to "34"))
            val pre34 = installer.preflight(sdk34Session, pkg).getOrThrow()
            assertTrue(pre34.selection.splits.all { it.entry.zipEntryName!!.endsWith("_3.apk") })

            val oldSession = FakeTvSession(preflightOutputs(pkg = "com.example.multi") + (TvCommands.PROP_SDK to "19"))
            assertEquals(InstallError.OlderSdk, installer.preflight(oldSession, pkg).installErrorOrNull())

            val garbageSession = FakeTvSession(preflightOutputs(pkg = "com.example.multi") + (TvCommands.PROP_SDK to "not-a-sdk"))
            assertEquals(InstallError.MixedBundle, installer.preflight(garbageSession, pkg).installErrorOrNull())

            // A single-variant bundle never asks the TV for its SDK level.
            val singleBytes = InstallTestFixtures.buildApkBytes(
                InstallTestFixtures.AxmlSpec(packageName = "com.example.single", versionCode = 7),
            )
            InstallTestFixtures.TempZipInstallSource.fromBytes("single.apks", singleBytes).use { singleSource ->
                val single = ApkInspector.inspect(singleSource).getOrThrow()
                val singleSession = FakeTvSession(preflightOutputs(pkg = "com.example.single"))
                installer.preflight(singleSession, single).getOrThrow()
                assertTrue(singleSession.sent.none { it == TvCommands.PROP_SDK })
            }
        }
    }

    @Test
    fun install_streams_single_and_split_apks_with_synthetic_names_and_progress() = runTest {
        var now = 1_000L
        val installer = AdbPackageInstaller(clock = { now })

        // 1. Single APK
        val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
        val (pkg, selection) = singlePkg("com.example.app", 100L, apkBytes)
        val source = InstallTestFixtures.ByteArrayInstallSource("Example.apk", apkBytes)

        val invocations = mutableListOf<Pair<List<String>, RecordingInstallStream>>()
        val helperApk = "/data/app/sidespeed/base.apk"
        val session = FakeTvSession(
            outputs = mapOf(
                TvCommands.SERIAL to "SER-1",
                TvCommands.PKG_PATH to "package:$helperApk",
                TvCommands.PIDOF to "42",
                TvCommands.cmdline(42) to "app_process\u0000/system/bin\u0000--apk=$helperApk",
                TvCommands.callCompilePackage("com.example.app", keepOptimized = true) to
                    "Result: Bundle[{ok=true, queued=1, req_seq=5}]",
            ),
            packageCmdHandler = { args ->
                val list = args.toList()
                val reply = when (list.firstOrNull()) {
                    "install-create" -> "Success: created install session [77]"
                    "install-write" -> "Success: streamed ${apkBytes.size} bytes"
                    "install-commit" -> "Success"
                    else -> error("Unexpected cmd: $list")
                }
                RecordingInstallStream(reply).also { invocations += list to it }
            },
        )
        val phases = mutableListOf<InstallTransferPhase>()
        val abandoned = mutableListOf<String>()

        val result = installer.install(
            session = session,
            tvSerial = "SER-1",
            pkg = pkg,
            selection = selection,
            source = source,
            keepOptimized = true,
            onPhase = {
                now += 150L
                phases += it
            },
            abandonOnFreshSession = { abandoned += it },
        ).getOrThrow()

        assertEquals(
            listOf(
                listOf("install-create", "-r", "-S", apkBytes.size.toString()),
                listOf("install-write", "-S", apkBytes.size.toString(), "77", "base.apk", "-"),
                listOf("install-commit", "77"),
            ),
            invocations.map { it.first },
        )
        assertTrue(invocations.all { it.second.closed })
        assertTrue(invocations[1].second.flushed)
        assertContentEquals(apkBytes, invocations[1].second.written.toByteArray())
        assertTrue(abandoned.isEmpty())
        assertTrue(phases.any { it is InstallTransferPhase.Transferring && it.bytesSent == apkBytes.size.toLong() })
        assertTrue(InstallTransferPhase.Committing in phases)
        assertTrue(InstallTransferPhase.StartingCompile in phases)
        assertTrue(phases.indexOf(InstallTransferPhase.Committing) < phases.indexOf(InstallTransferPhase.StartingCompile))
        assertEquals(
            InstallOutcome(
                packageName = "com.example.app",
                displayLabel = "Example",
                compileRequested = true,
                reqSeq = 5L,
                tvNeedsUpdate = false,
                helperVisit = VisitResult.Running,
            ),
            result,
        )
        assertTrue(TvCommands.callCompilePackage("com.example.app", true) in session.sent)

        // 2. Multi-split bundle with untrusted entry names -> synthetic safeName only
        val baseBytes = "base-content".encodeToByteArray()
        val abiBytes = "abi-content".encodeToByteArray()
        val langBytes = "lang-content".encodeToByteArray()
        val bundleZip = InstallTestFixtures.buildZipBytes(
            listOf(
                "splits/../evil base name;rm -rf.apk" to baseBytes,
                "splits/weird arm64 split.apk" to abiBytes,
                "splits/locale fr.apk" to langBytes,
            ),
        )
        val zipSource = InstallTestFixtures.TempZipInstallSource.fromBytes("bundle.apks", bundleZip)
        zipSource.use { src ->
            val baseMeta = ApkEntryMeta(
                "splits/../evil base name;rm -rf.apk",
                baseBytes.size.toLong(),
                ApkManifestInfo("com.example.bundle", 200L, "2.0", null, null, false, "Bundle"),
            )
            val abiMeta = ApkEntryMeta(
                "splits/weird arm64 split.apk",
                abiBytes.size.toLong(),
                ApkManifestInfo("com.example.bundle", 200L, "2.0", "config.arm64_v8a", null, false, null),
            )
            val langMeta = ApkEntryMeta(
                "splits/locale fr.apk",
                langBytes.size.toLong(),
                ApkManifestInfo("com.example.bundle", 200L, "2.0", "config.fr", null, false, null),
            )
            val bundlePkg = InspectedPackage("com.example.bundle", "Bundle", 200L, "2.0", true, baseMeta, listOf(abiMeta, langMeta))
            val bundleSel = SplitSelection(
                splits = listOf(
                    SelectedSplit(baseMeta, "base.apk", null),
                    SelectedSplit(abiMeta, "split_1.apk", "arm64-v8a"),
                    SelectedSplit(langMeta, "split_2.apk", "fr"),
                ),
                totalBytes = (baseBytes.size + abiBytes.size + langBytes.size).toLong(),
                summaryTags = listOf("arm64-v8a", "fr"),
            )
            val splitInvocations = mutableListOf<Pair<List<String>, RecordingInstallStream>>()
            val splitSession = FakeTvSession(
                outputs = mapOf(
                    TvCommands.SERIAL to "SER-1",
                    TvCommands.PKG_PATH to "package:$helperApk",
                    TvCommands.PIDOF to "42",
                    TvCommands.cmdline(42) to "app_process\u0000/system/bin\u0000--apk=$helperApk",
                    TvCommands.callCompilePackage("com.example.bundle", keepOptimized = false) to
                        "Result: Bundle[{ok=true, queued=1, req_seq=6}]",
                ),
                packageCmdHandler = { args ->
                    val list = args.toList()
                    val reply = when (list.firstOrNull()) {
                        "install-create" -> "Success: created install session [88]"
                        "install-write" -> "Success"
                        "install-commit" -> "Success"
                        else -> error("Unexpected cmd: $list")
                    }
                    RecordingInstallStream(reply).also { splitInvocations += list to it }
                },
            )
            val splitOutcome = installer.install(
                session = splitSession,
                tvSerial = "SER-1",
                pkg = bundlePkg,
                selection = bundleSel,
                source = src,
                keepOptimized = false,
                onPhase = {},
                abandonOnFreshSession = { error("should not abandon") },
            ).getOrThrow()

            assertEquals(
                listOf(
                    listOf("install-create", "-r", "-S", bundleSel.totalBytes.toString()),
                    listOf("install-write", "-S", baseBytes.size.toString(), "88", "base.apk", "-"),
                    listOf("install-write", "-S", abiBytes.size.toString(), "88", "split_1.apk", "-"),
                    listOf("install-write", "-S", langBytes.size.toString(), "88", "split_2.apk", "-"),
                    listOf("install-commit", "88"),
                ),
                splitInvocations.map { it.first },
            )
            assertContentEquals(baseBytes, splitInvocations[1].second.written.toByteArray())
            assertContentEquals(abiBytes, splitInvocations[2].second.written.toByteArray())
            assertContentEquals(langBytes, splitInvocations[3].second.written.toByteArray())
            assertEquals(6L, splitOutcome.reqSeq)
            assertTrue(splitOutcome.compileRequested)
        }

        // 3. Installing SideSpeed itself skips callCompilePackage
        val selfBytes = ByteArray(128) { 7 }
        val (selfPkg, selfSel) = singlePkg(SideSpeedPackages.RELEASE, 900L, selfBytes)
        val selfSession = FakeTvSession(
            outputs = mapOf(
                TvCommands.SERIAL to "SER-1",
                TvCommands.PKG_PATH to "package:$helperApk",
                TvCommands.PIDOF to "42",
                TvCommands.cmdline(42) to "app_process\u0000/system/bin\u0000--apk=$helperApk",
            ),
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [99]")
                    else -> RecordingInstallStream("Success")
                }
            },
        )
        val selfOutcome = installer.install(
            session = selfSession,
            tvSerial = "SER-1",
            pkg = selfPkg,
            selection = selfSel,
            source = InstallTestFixtures.ByteArrayInstallSource("SideSpeed.apk", selfBytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = {},
        ).getOrThrow()
        assertFalse(selfOutcome.compileRequested)
        assertFalse(selfOutcome.tvNeedsUpdate)
        assertTrue(selfSession.sent.none { it.contains("compile_package") })
    }

    @Test
    fun install_refuses_unresolved_multi_variant_package() = runTest {
        val installer = AdbPackageInstaller()
        val bytes = ByteArray(64) { it.toByte() }
        val (single, selection) = singlePkg("com.example.multi", 500L, bytes)
        val unresolved = single.copy(
            isBundle = true,
            variants = listOf(
                InspectedVariant(
                    minSdk = 21,
                    base = single.base,
                    splits = emptyList(),
                ),
            ),
        )
        val result = installer.install(
            session = FakeTvSession(),
            tvSerial = "SER-1",
            pkg = unresolved,
            selection = selection,
            source = InstallTestFixtures.ByteArrayInstallSource("multi.apks", bytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = {},
        )
        assertEquals(InstallError.CorruptArchive, result.installErrorOrNull())
    }

    @Test
    fun install_abandon_runs_on_cancel_and_write_failure() = runBlocking {
        val installer = AdbPackageInstaller()
        val bigBytes = ByteArray(128 * 1024) { 3 }
        val (pkg, selection) = singlePkg("com.example.cancel", 100L, bigBytes)

        // A. Cancellation mid-stream closes active stream and calls abandonOnFreshSession
        val writeEntered = CompletableDeferred<Unit>()
        lateinit var activeWriteStream: RecordingInstallStream
        val cancelSession = FakeTvSession(
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [101]")
                    "install-write" -> RecordingInstallStream(
                        reply = "Success",
                        onFirstWrite = { writeEntered.complete(Unit) },
                        blockWriteUntilClosed = true,
                    ).also { activeWriteStream = it }
                    else -> error("unexpected cmd ${args.toList()}")
                }
            },
        )
        val cancelledAbandoned = mutableListOf<String>()
        var cancelResult: Result<InstallOutcome>? = null
        val job = launch(Dispatchers.Default) {
            cancelResult = installer.install(
                session = cancelSession,
                tvSerial = "SER-1",
                pkg = pkg,
                selection = selection,
                source = InstallTestFixtures.ByteArrayInstallSource("big.apk", bigBytes),
                keepOptimized = true,
                onPhase = {},
                abandonOnFreshSession = { cancelledAbandoned += it },
            )
        }
        writeEntered.await()
        job.cancel()
        job.join()
        assertTrue(activeWriteStream.closed)
        assertTrue(job.isCancelled)
        assertNull(cancelResult)
        assertEquals(listOf("101"), cancelledAbandoned)

        // B. Write IOException closes stream, abandons session, and returns TvUnavailable
        lateinit var failedWriteStream: RecordingInstallStream
        val ioSession = FakeTvSession(
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [202]")
                    "install-write" -> RecordingInstallStream(
                        reply = "Success",
                        failOnWriteAfterBytes = 1024,
                    ).also { failedWriteStream = it }
                    else -> error("unexpected cmd ${args.toList()}")
                }
            },
        )
        val writeAbandoned = mutableListOf<String>()
        val writeResult = installer.install(
            session = ioSession,
            tvSerial = "SER-1",
            pkg = pkg,
            selection = selection,
            source = InstallTestFixtures.ByteArrayInstallSource("big.apk", bigBytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = { writeAbandoned += it },
        )
        assertTrue(failedWriteStream.closed)
        assertEquals(listOf("202"), writeAbandoned)
        assertEquals(InstallError.TvUnavailable, writeResult.installErrorOrNull())

        // C. Unsupported stream transport
        val legacySession = FakeTvSession()
        val unsupported = installer.install(
            session = legacySession,
            tvSerial = "SER-1",
            pkg = pkg,
            selection = selection,
            source = InstallTestFixtures.ByteArrayInstallSource("big.apk", bigBytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = { error("should not abandon") },
        )
        assertEquals(InstallError.UnsupportedStreamTransport, unsupported.installErrorOrNull())
    }

    @Test
    fun install_maps_all_known_failure_codes_and_fallback_token() = runTest {
        val installer = AdbPackageInstaller()
        val apkBytes = ByteArray(128) { 1 }
        val (pkg, selection) = singlePkg("com.example.fail", 100L, apkBytes)

        val cases = listOf(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]" to
                InstallError.InstallFailed(InstallFailureCode.UPDATE_INCOMPATIBLE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"),
            "Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: cert mismatch]" to
                InstallError.InstallFailed(InstallFailureCode.UPDATE_INCOMPATIBLE, "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES"),
            "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]" to
                InstallError.InstallFailed(InstallFailureCode.VERSION_DOWNGRADE, "INSTALL_FAILED_VERSION_DOWNGRADE"),
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]" to
                InstallError.InstallFailed(InstallFailureCode.INSUFFICIENT_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE"),
            "Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries]" to
                InstallError.InstallFailed(InstallFailureCode.NO_MATCHING_ABIS, "INSTALL_FAILED_NO_MATCHING_ABIS"),
            "Failure [INSTALL_FAILED_OLDER_SDK]" to
                InstallError.InstallFailed(InstallFailureCode.OLDER_SDK, "INSTALL_FAILED_OLDER_SDK"),
            "Failure [INSTALL_FAILED_INVALID_APK: missing manifest]" to
                InstallError.InstallFailed(InstallFailureCode.INVALID_APK, "INSTALL_FAILED_INVALID_APK"),
            "Failure [INSTALL_PARSE_FAILED_BAD_MANIFEST: bad xml]" to
                InstallError.InstallFailed(InstallFailureCode.INVALID_APK, "INSTALL_PARSE_FAILED_BAD_MANIFEST"),
            "Failure [INSTALL_FAILED_CUSTOM_REASON: line1\nline2 stderr detail]" to
                InstallError.InstallFailed(InstallFailureCode.UNKNOWN, "INSTALL_FAILED_CUSTOM_REASON"),
        )

        for ((commitReply, expectedError) in cases) {
            val session = FakeTvSession(
                packageCmdHandler = { args ->
                    when (args.first()) {
                        "install-create" -> RecordingInstallStream("Success: created install session [33]")
                        "install-write" -> RecordingInstallStream("Success")
                        "install-commit" -> RecordingInstallStream(commitReply)
                        else -> error("unexpected ${args.toList()}")
                    }
                },
            )
            val result = installer.install(
                session = session,
                tvSerial = "SER-1",
                pkg = pkg,
                selection = selection,
                source = InstallTestFixtures.ByteArrayInstallSource("fail.apk", apkBytes),
                keepOptimized = true,
                onPhase = {},
                abandonOnFreshSession = {},
            )
            assertEquals(expectedError, result.installErrorOrNull(), commitReply)
        }
    }

    @Test
    fun install_with_sidespeed_missing_on_tv_is_plain_success_without_update_note() = runTest {
        val installer = AdbPackageInstaller()
        val apkBytes = ByteArray(128) { 2 }
        val (pkg, selection) = singlePkg("com.example.nospeed", 100L, apkBytes)
        val session = FakeTvSession(
            outputs = mapOf(TvCommands.SERIAL to "SER-1", TvCommands.PKG_PATH to ""),
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [45]")
                    else -> RecordingInstallStream("Success")
                }
            },
        )
        val outcome = installer.install(
            session = session,
            tvSerial = "SER-1",
            pkg = pkg,
            selection = selection,
            source = InstallTestFixtures.ByteArrayInstallSource("a.apk", apkBytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = {},
        ).getOrThrow()
        assertFalse(outcome.compileRequested)
        assertFalse(outcome.tvNeedsUpdate)
        assertTrue(session.sent.none { it.contains("compile_package") })
    }

    @Test
    fun install_treats_older_tv_without_call_as_success_with_update_note() = runTest {
        val installer = AdbPackageInstaller()
        val apkBytes = ByteArray(128) { 2 }
        val (pkg, selection) = singlePkg("com.example.oldtv", 100L, apkBytes)
        val helperApk = "/data/app/sidespeed/base.apk"

        for (callResponse in listOf(
            "java.lang.UnsupportedOperationException: Unknown method compile_package",
            "Error while accessing provider:io.github.devjardins.sidespeed.status\nCould not find provider",
        )) {
            val session = FakeTvSession(
                outputs = mapOf(
                    TvCommands.SERIAL to "SER-1",
                    TvCommands.PKG_PATH to "package:$helperApk",
                    TvCommands.PIDOF to "42",
                    TvCommands.cmdline(42) to "app_process\u0000/system/bin\u0000--apk=$helperApk",
                    TvCommands.callCompilePackage("com.example.oldtv", keepOptimized = true) to callResponse,
                ),
                packageCmdHandler = { args ->
                    when (args.first()) {
                        "install-create" -> RecordingInstallStream("Success: created install session [44]")
                        else -> RecordingInstallStream("Success")
                    }
                },
            )
            val outcome = installer.install(
                session = session,
                tvSerial = "SER-1",
                pkg = pkg,
                selection = selection,
                source = InstallTestFixtures.ByteArrayInstallSource("old.apk", apkBytes),
                keepOptimized = true,
                onPhase = {},
                abandonOnFreshSession = {},
            ).getOrThrow()

            assertFalse(outcome.compileRequested)
            assertTrue(outcome.tvNeedsUpdate)
            assertNull(outcome.reqSeq)
        }
    }

    @Test
    fun tls_bound_session_refuses_openPackageCmd_when_tls_dropped() = runBlocking {
        var packageCmdDelegated = false
        val inner = object : TvSession {
            override var tlsNegotiated: Boolean = true
            var closed = false
            override suspend fun run(command: String): String = ""
            override suspend fun openPackageCmd(vararg args: String): InstallStream {
                packageCmdDelegated = true
                return RecordingInstallStream("Success")
            }
            override fun close() {
                closed = true
            }
        }
        val tv = SavedTv(
            id = "tv-1",
            name = "TV",
            address = "192.168.1.50",
            serial = "SER-1",
            trustedNetworkId = "home",
            link = TvLink.Encrypted(37_555),
        )
        val store = InMemoryCompanionStore(listOf(tv))
        val installOpens = mutableListOf<Pair<String, Int>>()
        val installConnector = TvConnector { address, port ->
            installOpens += address to port
            inner
        }
        val sessions = TvSessions(
            connector = TvConnector { _, _ -> error("poll connector must not be used by openForInstall") },
            store = store,
            installConnector = installConnector,
        )

        val bound = sessions.openForInstall(tv)
        assertEquals(listOf("192.168.1.50" to 37_555), installOpens)

        // Simulate TLS dropping after open
        inner.tlsNegotiated = false
        assertFailsWith<TlsNotNegotiated> {
            bound.openPackageCmd("install-create", "-r", "-S", "10")
        }
        assertFalse(packageCmdDelegated)
        assertTrue(inner.closed)
    }

    @Test
    fun cancellation_closes_session_and_stream_off_caller_thread_when_blocked_in_write_or_readAllText() = runBlocking {
        val installer = AdbPackageInstaller()
        val bytes = ByteArray(16 * 1024) { 9 }
        val (pkg, selection) = singlePkg("com.example.abort", 100L, bytes)
        val callerThread = Thread.currentThread()

        // Case 1: Blocked in write() until session.close() is invoked (simulating Kadb awaitAckBytes stall)
        val writeEntered = CountDownLatch(1)
        val sessionClosed1 = CountDownLatch(1)
        val closeThreads1 = Collections.synchronizedList(mutableListOf<Thread>())
        var stream1Closed = false
        val session1 = FakeTvSession(
            onClose = {
                closeThreads1 += Thread.currentThread()
                sessionClosed1.countDown()
            },
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [501]")
                    "install-write" -> object : InstallStream {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            writeEntered.countDown()
                            if (!sessionClosed1.await(5, TimeUnit.SECONDS)) {
                                throw AssertionError("session.close() was not called promptly")
                            }
                            throw IOException("socket closed")
                        }
                        override fun flush() = Unit
                        override fun readAllText(): String = ""
                        override fun close() {
                            stream1Closed = true
                            closeThreads1 += Thread.currentThread()
                        }
                    }
                    else -> error("unexpected ${args.toList()}")
                }
            },
        )
        val abandoned1 = mutableListOf<String>()
        val job1 = launch(Dispatchers.Default) {
            installer.install(
                session = session1,
                tvSerial = "SER-1",
                pkg = pkg,
                selection = selection,
                source = InstallTestFixtures.ByteArrayInstallSource("abort.apk", bytes),
                keepOptimized = true,
                onPhase = {},
                abandonOnFreshSession = { abandoned1 += it },
            )
        }
        assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
        job1.cancel()
        job1.join()
        assertTrue(session1.closed)
        assertTrue(stream1Closed)
        assertTrue(closeThreads1.isNotEmpty())
        assertTrue(closeThreads1.none { it === callerThread }, "close ran on cancelling thread: $closeThreads1")
        assertEquals(listOf("501"), abandoned1)

        // Case 2: Blocked in install-commit readAllText() until session.close() (stream.close() alone wouldn't unblock)
        val readEntered = CountDownLatch(1)
        val sessionClosed2 = CountDownLatch(1)
        val closeThreads2 = Collections.synchronizedList(mutableListOf<Thread>())
        val session2 = FakeTvSession(
            onClose = {
                closeThreads2 += Thread.currentThread()
                sessionClosed2.countDown()
            },
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [502]")
                    "install-write" -> RecordingInstallStream("Success")
                    "install-commit" -> object : InstallStream {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                        override fun flush() = Unit
                        override fun readAllText(): String {
                            readEntered.countDown()
                            if (!sessionClosed2.await(5, TimeUnit.SECONDS)) {
                                throw AssertionError("session.close() was not called promptly")
                            }
                            return ""
                        }
                        override fun close() {
                            closeThreads2 += Thread.currentThread()
                        }
                    }
                    else -> error("unexpected ${args.toList()}")
                }
            },
        )
        val abandoned2 = mutableListOf<String>()
        val job2 = launch(Dispatchers.Default) {
            installer.install(
                session = session2,
                tvSerial = "SER-1",
                pkg = pkg,
                selection = selection,
                source = InstallTestFixtures.ByteArrayInstallSource("abort.apk", bytes),
                keepOptimized = true,
                onPhase = {},
                abandonOnFreshSession = { abandoned2 += it },
            )
        }
        assertTrue(readEntered.await(5, TimeUnit.SECONDS))
        job2.cancel()
        job2.join()
        assertTrue(session2.closed)
        assertTrue(closeThreads2.isNotEmpty())
        assertTrue(closeThreads2.none { it === callerThread }, "close ran on cancelling thread: $closeThreads2")
        assertEquals(listOf("502"), abandoned2)
    }

    @Test
    fun blank_stream_reply_on_create_write_or_commit_maps_to_tv_unavailable() = runTest {
        val installer = AdbPackageInstaller()
        val bytes = ByteArray(128) { 4 }
        val (pkg, selection) = singlePkg("com.example.eof", 100L, bytes)

        // Blank reply on install-create -> TvUnavailable (no session to abandon)
        val createBlankSession = FakeTvSession(
            packageCmdHandler = { RecordingInstallStream("") },
        )
        val createAbandoned = mutableListOf<String>()
        val createRes = installer.install(
            session = createBlankSession,
            tvSerial = "SER-1",
            pkg = pkg,
            selection = selection,
            source = InstallTestFixtures.ByteArrayInstallSource("eof.apk", bytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = { createAbandoned += it },
        )
        assertEquals(InstallError.TvUnavailable, createRes.installErrorOrNull())
        assertTrue(createAbandoned.isEmpty())

        // Blank reply on install-write -> abandons + TvUnavailable
        val writeBlankSession = FakeTvSession(
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [601]")
                    else -> RecordingInstallStream("   \n")
                }
            },
        )
        val writeAbandoned = mutableListOf<String>()
        val writeRes = installer.install(
            session = writeBlankSession,
            tvSerial = "SER-1",
            pkg = pkg,
            selection = selection,
            source = InstallTestFixtures.ByteArrayInstallSource("eof.apk", bytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = { writeAbandoned += it },
        )
        assertEquals(InstallError.TvUnavailable, writeRes.installErrorOrNull())
        assertEquals(listOf("601"), writeAbandoned)

        // Blank reply on install-commit -> abandons + TvUnavailable
        val commitBlankSession = FakeTvSession(
            packageCmdHandler = { args ->
                when (args.first()) {
                    "install-create" -> RecordingInstallStream("Success: created install session [602]")
                    "install-write" -> RecordingInstallStream("Success")
                    else -> RecordingInstallStream("")
                }
            },
        )
        val commitAbandoned = mutableListOf<String>()
        val commitRes = installer.install(
            session = commitBlankSession,
            tvSerial = "SER-1",
            pkg = pkg,
            selection = selection,
            source = InstallTestFixtures.ByteArrayInstallSource("eof.apk", bytes),
            keepOptimized = true,
            onPhase = {},
            abandonOnFreshSession = { commitAbandoned += it },
        )
        assertEquals(InstallError.TvUnavailable, commitRes.installErrorOrNull())
        assertEquals(listOf("602"), commitAbandoned)
    }
}
