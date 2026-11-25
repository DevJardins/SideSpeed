package io.github.devjardins.sidespeed.companion.l10n

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class EnglishSnapshotTest {
    @Test
    fun english_text_unchanged_under_same_key() {
        val snapshotFile = EnglishSnapshotSupport.snapshotFile()
        val current = EnglishSnapshotSupport.entriesFromStringsXml()
        if (EnglishSnapshotSupport.updateSnapshotRequested()) {
            EnglishSnapshotSupport.writeSnapshot(snapshotFile, current)
            return
        }
        val snapshot = EnglishSnapshotSupport.readSnapshot(snapshotFile)
        val shared = current.keys.intersect(snapshot.keys)
        val changed = shared.filter { current.getValue(it) != snapshot.getValue(it) }.sorted()
        if (changed.isNotEmpty()) {
            val key = changed.first()
            fail(
                "English for $key changed. If the meaning changed, rename the key so translations start again. " +
                    "If it is only a typo or punctuation fix, update l10n/english-snapshot.tsv in the same commit.",
            )
        }
        assertTrue(changed.isEmpty())
    }

    @Test
    fun snapshot_lists_every_key() {
        val snapshotFile = EnglishSnapshotSupport.snapshotFile()
        val current = EnglishSnapshotSupport.entriesFromStringsXml()
        if (EnglishSnapshotSupport.updateSnapshotRequested()) {
            EnglishSnapshotSupport.writeSnapshot(snapshotFile, current)
            return
        }
        val snapshot = EnglishSnapshotSupport.readSnapshot(snapshotFile)
        val missing = (current.keys - snapshot.keys).sorted()
        val extra = (snapshot.keys - current.keys).sorted()
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            fail(
                "l10n/english-snapshot.tsv is out of date: run the test with -Dsidespeed.updateSnapshot=true and commit it.",
            )
        }
    }
}