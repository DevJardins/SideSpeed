package io.github.devjardins.sidespeed.companion.install

/**
 * Selects the base APK, feature splits, and device-matched ABI/density/locale config splits for a TV.
 */
object SplitSelector {
    private val CANONICAL_ABIS = mapOf(
        "arm64_v8a" to "arm64-v8a",
        "armeabi_v7a" to "armeabi-v7a",
        "armeabi" to "armeabi",
        "x86_64" to "x86_64",
        "x86" to "x86",
        "riscv64" to "riscv64",
        "mips64" to "mips64",
        "mips" to "mips",
    )

    private val DENSITY_DPI = mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
    )

    private val UNIVERSAL_DENSITIES = setOf("anydpi", "nodpi")
    private val NUMERIC_DPI_PATTERN = Regex("^(\\d+)dpi$")
    private val LOCALE_PATTERN = Regex("^(?:[a-zA-Z]{2,3}(?:[-_+][a-zA-Z0-9]+)*|b\\+[a-zA-Z]{2,3}(?:\\+[a-zA-Z0-9]+)*)$")
    private val ANDROID_RESOURCE_REGION = Regex("^r[A-Z]{2}$")
    private val LEGACY_LANGUAGE_MAP = mapOf(
        "iw" to "he",
        "in" to "id",
        "ji" to "yi",
    )

    private sealed interface SplitKind {
        data object Feature : SplitKind
        data class Abi(val group: String, val canonicalAbi: String) : SplitKind
        data class Density(val group: String, val tag: String, val dpi: Int?) : SplitKind
        data class Locale(val group: String, val language: String, val region: String?, val displayTag: String) : SplitKind
        data class UnknownConfig(val group: String) : SplitKind
    }

    private data class ParsedLocale(val language: String, val region: String?, val displayTag: String)

    fun select(pkg: InspectedPackage, device: DeviceProfile): Result<SplitSelection> {
        if (!pkg.isBundle) {
            if (pkg.base.sizeBytes < 0L || pkg.base.sizeBytes > ApkInspector.MAX_INSTALL_BYTES) {
                return InstallError.TooLarge.asFailure()
            }
            return Result.success(
                SplitSelection(
                    splits = listOf(SelectedSplit(pkg.base, "base.apk", null)),
                    totalBytes = pkg.base.sizeBytes,
                    summaryTags = emptyList(),
                ),
            )
        }

        val featureSplits = ArrayList<ApkEntryMeta>()
        val abisByGroup = LinkedHashMap<String, MutableList<Pair<ApkEntryMeta, SplitKind.Abi>>>()
        val densitiesByGroup = LinkedHashMap<String, MutableList<Pair<ApkEntryMeta, SplitKind.Density>>>()
        val localesByGroup = LinkedHashMap<String, MutableList<Pair<ApkEntryMeta, SplitKind.Locale>>>()

        for (split in pkg.splits) {
            when (val kind = classify(split)) {
                SplitKind.Feature -> featureSplits += split
                is SplitKind.Abi -> abisByGroup.getOrPut(kind.group) { ArrayList() } += split to kind
                is SplitKind.Density -> densitiesByGroup.getOrPut(kind.group) { ArrayList() } += split to kind
                is SplitKind.Locale -> localesByGroup.getOrPut(kind.group) { ArrayList() } += split to kind
                is SplitKind.UnknownConfig -> Unit
            }
        }

        val chosen = ArrayList<Pair<ApkEntryMeta, String?>>()
        chosen += pkg.base to null

        if (!selectConfigGroup("", device, abisByGroup, densitiesByGroup, localesByGroup, chosen)) {
            return InstallError.IncompatibleAbi.asFailure()
        }
        val visitedGroups = HashSet<String>().apply { add("") }
        for (feature in featureSplits) {
            val featureName = feature.manifest.splitName
            chosen += feature to featureName
            if (featureName != null &&
                visitedGroups.add(featureName) &&
                !selectConfigGroup(featureName, device, abisByGroup, densitiesByGroup, localesByGroup, chosen)
            ) {
                return InstallError.IncompatibleAbi.asFailure()
            }
        }

        var totalBytes = 0L
        val selected = ArrayList<SelectedSplit>(chosen.size)
        chosen.forEachIndexed { index, (entry, tag) ->
            if (entry.sizeBytes < 0L || entry.sizeBytes > ApkInspector.MAX_INSTALL_BYTES - totalBytes) {
                return InstallError.TooLarge.asFailure()
            }
            totalBytes += entry.sizeBytes
            val safeName = if (index == 0) "base.apk" else "split_$index.apk"
            selected += SelectedSplit(entry = entry, safeName = safeName, summaryTag = tag)
        }

        return Result.success(
            SplitSelection(
                splits = selected,
                totalBytes = totalBytes,
                summaryTags = selected.mapNotNull { it.summaryTag }.distinct(),
            ),
        )
    }

    private fun selectConfigGroup(
        group: String,
        device: DeviceProfile,
        abisByGroup: Map<String, List<Pair<ApkEntryMeta, SplitKind.Abi>>>,
        densitiesByGroup: Map<String, List<Pair<ApkEntryMeta, SplitKind.Density>>>,
        localesByGroup: Map<String, List<Pair<ApkEntryMeta, SplitKind.Locale>>>,
        out: MutableList<Pair<ApkEntryMeta, String?>>,
    ): Boolean {
        val abiCandidates = abisByGroup[group].orEmpty()
        if (abiCandidates.isNotEmpty()) {
            val match = pickAbi(abiCandidates, device.abiList) ?: return false
            out += match.first to match.second.canonicalAbi
        }

        val densityCandidates = densitiesByGroup[group].orEmpty()
        if (densityCandidates.isNotEmpty()) {
            val match = pickDensity(densityCandidates, device.densityDpi)
            if (match != null) {
                out += match.first to match.second.tag
            }
        }

        val localeCandidates = localesByGroup[group].orEmpty()
        if (localeCandidates.isNotEmpty()) {
            val match = pickLocale(localeCandidates, device.localeTag)
            if (match != null) {
                out += match.first to match.second.displayTag
            }
        }
        return true
    }

    private fun pickAbi(
        candidates: List<Pair<ApkEntryMeta, SplitKind.Abi>>,
        deviceAbis: List<String>,
    ): Pair<ApkEntryMeta, SplitKind.Abi>? {
        for (rawAbi in deviceAbis) {
            val canonical = canonicalAbi(rawAbi) ?: continue
            val match = candidates.firstOrNull { it.second.canonicalAbi == canonical }
            if (match != null) return match
        }
        return null
    }

    private fun pickDensity(
        candidates: List<Pair<ApkEntryMeta, SplitKind.Density>>,
        targetDpi: Int,
    ): Pair<ApkEntryMeta, SplitKind.Density>? {
        val numeric = candidates.filter { it.second.dpi != null }
        if (numeric.isNotEmpty()) {
            if (targetDpi > 0) {
                numeric.firstOrNull { it.second.dpi == targetDpi }?.let { return it }
                numeric.filter { it.second.dpi!! >= targetDpi }.minByOrNull { it.second.dpi!! }?.let { return it }
            }
            return numeric.maxByOrNull { it.second.dpi!! }
        }
        return candidates.firstOrNull { it.second.tag == "anydpi" }
            ?: candidates.firstOrNull { it.second.tag == "nodpi" }
            ?: candidates.firstOrNull()
    }

    private fun pickLocale(
        candidates: List<Pair<ApkEntryMeta, SplitKind.Locale>>,
        deviceLocaleTag: String,
    ): Pair<ApkEntryMeta, SplitKind.Locale>? {
        val target = parseLocale(deviceLocaleTag)
        if (target != null) {
            if (target.region != null) {
                candidates.firstOrNull {
                    it.second.language == target.language && it.second.region == target.region
                }?.let { return it }
            }
            candidates.firstOrNull {
                it.second.language == target.language && it.second.region == null
            }?.let { return it }
            candidates.firstOrNull {
                it.second.language == target.language
            }?.let { return it }
        }
        candidates.firstOrNull { it.second.language == "en" && it.second.region == null }?.let { return it }
        candidates.firstOrNull { it.second.language == "en" }?.let { return it }
        return candidates.firstOrNull()
    }

    private fun classify(entry: ApkEntryMeta): SplitKind {
        val manifest = entry.manifest
        if (manifest.isFeatureSplit) return SplitKind.Feature
        val splitName = manifest.splitName ?: return SplitKind.Feature
        val configForSplit = manifest.configForSplit?.takeIf { it.isNotBlank() }

        val token: String
        val group: String
        when {
            splitName.startsWith("config.") -> {
                token = splitName.removePrefix("config.")
                group = configForSplit ?: ""
            }
            configForSplit != null && splitName.startsWith("$configForSplit.config.") -> {
                token = splitName.removePrefix("$configForSplit.config.")
                group = configForSplit
            }
            ".config." in splitName -> {
                token = splitName.substringAfterLast(".config.")
                group = configForSplit ?: splitName.substringBefore(".config.")
            }
            else -> return SplitKind.Feature
        }

        canonicalAbi(token)?.let { return SplitKind.Abi(group, it) }

        val lowerToken = token.lowercase()
        DENSITY_DPI[lowerToken]?.let { return SplitKind.Density(group, lowerToken, it) }
        if (lowerToken in UNIVERSAL_DENSITIES) return SplitKind.Density(group, lowerToken, null)
        NUMERIC_DPI_PATTERN.matchEntire(lowerToken)?.let { m ->
            val dpi = m.groupValues[1].toIntOrNull()
            if (dpi != null && dpi > 0) return SplitKind.Density(group, lowerToken, dpi)
        }

        if (LOCALE_PATTERN.matches(token)) {
            parseLocale(token)?.let {
                return SplitKind.Locale(group, it.language, it.region, it.displayTag)
            }
        }
        return SplitKind.UnknownConfig(group)
    }

    private fun canonicalAbi(raw: String): String? {
        val normalized = raw.trim().lowercase().replace('-', '_')
        return CANONICAL_ABIS[normalized]
    }

    private fun parseLocale(raw: String): ParsedLocale? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = if (trimmed.startsWith("b+")) {
            trimmed.removePrefix("b+").split('+')
        } else {
            trimmed.split('-', '_')
        }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val rawLang = parts[0].lowercase()
        if (rawLang.length !in 2..3 || !rawLang.all { it in 'a'..'z' }) return null
        val language = LEGACY_LANGUAGE_MAP[rawLang] ?: rawLang

        var region: String? = null
        for (i in 1 until parts.size) {
            val part = parts[i]
            val candidate = if (ANDROID_RESOURCE_REGION.matches(part)) part.substring(1) else part
            val isRegion = (candidate.length == 2 && candidate.all { it in 'a'..'z' || it in 'A'..'Z' }) ||
                (candidate.length == 3 && candidate.all { it in '0'..'9' })
            if (isRegion) {
                region = candidate.lowercase()
                break
            }
        }
        val displayTag = if (region != null) "$language-${region.uppercase()}" else language
        return ParsedLocale(language = language, region = region, displayTag = displayTag)
    }
}
