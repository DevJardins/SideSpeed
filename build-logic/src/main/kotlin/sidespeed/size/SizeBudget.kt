package sidespeed.size

import java.util.Locale

/** One release output folder and the most any file of [extension] in it may weigh. */
data class Budget(val key: String, val dir: String, val extension: String, val maxBytes: Long)

object SizeBudget {
    const val MIB = 1024L * 1024L

    /** Budgets named in a comma-separated [only] list, or all of them when [only] is null or blank. */
    fun select(budgets: List<Budget>, only: String?): List<Budget> {
        if (only.isNullOrBlank()) return budgets
        val keys = only.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val byKey = budgets.associateBy { it.key }
        return keys.map { byKey[it] ?: throw IllegalArgumentException("Unknown size budget '$it'. Known: ${byKey.keys.joinToString()}") }
    }

    /**
     * One message per budget with no matching file and per file over its budget.
     * [found] maps a budget key to the (file name, size in bytes) pairs found in its folder.
     */
    fun check(budgets: List<Budget>, found: Map<String, List<Pair<String, Long>>>): List<String> =
        budgets.flatMap { budget ->
            val files = found[budget.key].orEmpty()
            if (files.isEmpty()) {
                listOf("${budget.key}: no .${budget.extension} file in ${budget.dir}")
            } else {
                files.filter { (_, size) -> size > budget.maxBytes }.map { (name, size) ->
                    "${budget.key}: $name is ${mib(size)}, over its ${mib(budget.maxBytes)} budget"
                }
            }
        }

    private fun mib(bytes: Long) = String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / MIB)
}
