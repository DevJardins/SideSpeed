package io.github.devjardins.sidespeed.maintenance

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTypeTest {
    @Test fun android14UsesSpecialUseWhichHasNoTimeoutAndIsAllowedAtBoot() =
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, foregroundServiceType(34))

    @Test fun android10To13UseDataSync() =
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundServiceType(30))

    @Test fun olderThanAndroid10HasNoType() = assertNull(foregroundServiceType(28))
}
