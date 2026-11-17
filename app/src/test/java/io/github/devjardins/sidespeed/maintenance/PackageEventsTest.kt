package io.github.devjardins.sidespeed.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageEventsTest {
    private val added = "android.intent.action.PACKAGE_ADDED"
    private val replaced = "android.intent.action.PACKAGE_REPLACED"
    private val removed = "android.intent.action.PACKAGE_REMOVED"

    @Test fun replacedTriggersScan() = assertEquals("com.x.y", packageEventTarget(replaced, "com.x.y", "own.pkg", false))
    @Test fun addedTriggersScan() = assertEquals("com.x.y", packageEventTarget(added, "com.x.y", "own.pkg", false))
    @Test fun addedWhileReplacingIgnored() = assertNull(packageEventTarget(added, "com.x.y", "own.pkg", true))
    @Test fun ownPackageIgnored() = assertNull(packageEventTarget(replaced, "own.pkg", "own.pkg", false))
    @Test fun removedIgnored() = assertNull(packageEventTarget(removed, "com.x.y", "own.pkg", false))
    @Test fun nullDataIgnored() = assertNull(packageEventTarget(replaced, null, "own.pkg", false))
}
