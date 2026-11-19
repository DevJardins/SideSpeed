package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsCommandsTest {
    private fun commands(helperUp: Boolean = true): Pair<FakeAdbBackend, ToolsCommands> {
        val fake = FakeAdbBackend()
        return fake to ToolsCommands(fake) { helperUp }
    }

    @Test fun refusedMapsToRefused() = runTest {
        val (fake, commands) = commands()
        fake.on("pm trim-caches 999999999999999") { listOf("error: request not allowed") to 126 }
        assertEquals(ToolResult.Refused, commands.trimCaches(999_999_999_999_999))
    }

    @Test fun refusedPutIsRefusedNotOutdated() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("settings put global window_animation_scale 0.5") { listOf("error: request not allowed") to 126 }
        }
        // An allowlist refusal must not tell the user to restart the helper.
        assertEquals(ToolResult.Refused, commands.putAnimationScale(AnimationKey.WINDOW, "0.5"))
    }

    @Test fun helperDownShortCircuits() = runTest {
        val (fake, commands) = commands(helperUp = false)
        assertEquals(ToolResult.HelperDown, commands.trimCaches(500_000_000))
        assertEquals(ToolResult.HelperDown, commands.getAnimationScale(AnimationKey.WINDOW))
        assertEquals(ToolResult.HelperDown, commands.putAnimationScale(AnimationKey.WINDOW, "0.5"))
        assertEquals(ToolResult.HelperDown, commands.resetCompiled("com.x.y"))
        assertEquals(ToolResult.HelperDown, commands.launchTimeMs("com.x.y", "com.x.y.Main"))
        assertEquals(ToolResult.HelperDown, commands.bringBack())
        assertTrue(fake.commands.isEmpty())
    }

    @Test fun trimCachesOk() = runTest {
        val (fake, commands) = commands()
        fake.on("pm trim-caches 999999999999999") { emptyList<String>() to 0 }
        assertEquals(ToolResult.Ok(Unit), commands.trimCaches(999_999_999_999_999))
        assertEquals(listOf("pm trim-caches 999999999999999"), fake.commands)
    }

    @Test fun trimCachesFailed() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("pm trim-caches 500000000") { listOf("Failure") to 1 }
        }
        val result = commands.trimCaches(500_000_000)
        assertTrue(result is ToolResult.Failed)
    }

    @Test fun getAnimationScaleValue() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("settings get global window_animation_scale") { listOf("0.5") to 0 }
        }
        assertEquals(ToolResult.Ok("0.5"), commands.getAnimationScale(AnimationKey.WINDOW))
    }

    @Test fun getAnimationScaleNullMapsToNull() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("settings get global animator_duration_scale") { listOf("null") to 0 }
        }
        assertEquals(ToolResult.Ok(null), commands.getAnimationScale(AnimationKey.ANIMATOR))
    }

    @Test fun putAnimationScaleOk() = runTest {
        val (fake, commands) = commands()
        fake.on("settings put global transition_animation_scale 0.5") { emptyList<String>() to 0 }
        assertEquals(
            ToolResult.Ok(Unit),
            commands.putAnimationScale(AnimationKey.TRANSITION, "0.5"),
        )
        assertEquals(listOf("settings put global transition_animation_scale 0.5"), fake.commands)
    }

    @Test fun resetCompiledSendsVerifyForm() = runTest {
        val (fake, commands) = commands()
        fake.on("pm compile -m verify -f com.x.y") { listOf("Success") to 0 }
        assertEquals(ToolResult.Ok(Unit), commands.resetCompiled("com.x.y"))
        assertEquals(listOf("pm compile -m verify -f com.x.y"), fake.commands)
    }

    @Test fun launchTimeMsParsesTotalTime() = runTest {
        val (fake, commands) = commands()
        fake.on("launch-time com.x.y com.x.y.Main") {
            listOf("Starting: Intent {}", "TotalTime: 1234") to 0
        }
        assertEquals(ToolResult.Ok(1234L), commands.launchTimeMs("com.x.y", "com.x.y.Main"))
        assertEquals(listOf("launch-time com.x.y com.x.y.Main"), fake.commands)
    }

    @Test fun launchTimeMsErrorIsFailed() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("launch-time com.x.y com.x.y.Main") {
                listOf("Error: Activity not started, unable to resolve Intent {}") to 0
            }
        }
        val result = commands.launchTimeMs("com.x.y", "com.x.y.Main")
        assertTrue(result is ToolResult.Failed)
    }

    @Test fun launchTimeMsMissingTotalTimeIsFailed() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("launch-time com.x.y com.x.y.Main") { listOf("Starting: Intent {}") to 0 }
        }
        assertTrue(commands.launchTimeMs("com.x.y", "com.x.y.Main") is ToolResult.Failed)
    }

    @Test fun launchTimeMsRefusedIsRefused() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("launch-time com.x.y com.x.y.Main") { listOf("error: request not allowed") to 126 }
        }
        assertEquals(ToolResult.Refused, commands.launchTimeMs("com.x.y", "com.x.y.Main"))
    }

    @Test fun setHomeSendsExactForm() = runTest {
        val (fake, commands) = commands()
        fake.on("set-home com.example.home com.example.home.MainActivity") { emptyList<String>() to 0 }
        assertEquals(ToolResult.Ok(Unit), commands.setHome("com.example.home", "com.example.home.MainActivity"))
        assertEquals(listOf("set-home com.example.home com.example.home.MainActivity"), fake.commands)
    }

    @Test fun enableAndDisableSendExactForms() = runTest {
        val (fake, commands) = commands()
        fake.on("pm enable com.example.bloat") { emptyList<String>() to 0 }
        fake.on("pm disable-user --user 0 com.example.bloat") { emptyList<String>() to 0 }
        assertEquals(ToolResult.Ok(Unit), commands.enablePackage("com.example.bloat"))
        assertEquals(ToolResult.Ok(Unit), commands.disablePackage("com.example.bloat"))
        assertEquals(
            listOf("pm enable com.example.bloat", "pm disable-user --user 0 com.example.bloat"),
            fake.commands,
        )
    }

    @Test fun disabledPackagesParsesList() = runTest {
        val (_, commands) = commands().also { (fake, _) ->
            fake.on("pm list packages -d --user 0") {
                listOf("package:com.example.off", "package:com.example.alsooff") to 0
            }
        }
        assertEquals(
            ToolResult.Ok(setOf("com.example.off", "com.example.alsooff")),
            commands.disabledPackages(),
        )
    }

    @Test fun homeCommandsHelperDownShortCircuits() = runTest {
        val (fake, commands) = commands(helperUp = false)
        assertEquals(ToolResult.HelperDown, commands.setHome("com.example.home", "com.example.home.MainActivity"))
        assertEquals(ToolResult.HelperDown, commands.enablePackage("com.example.bloat"))
        assertEquals(ToolResult.HelperDown, commands.disablePackage("com.example.bloat"))
        assertEquals(ToolResult.HelperDown, commands.disabledPackages())
        assertTrue(fake.commands.isEmpty())
    }

    @Test fun bringBackOk() = runTest {
        val (fake, commands) = commands()
        fake.on("bring-back") { listOf("Starting: Intent {}") to 0 }
        assertEquals(ToolResult.Ok(Unit), commands.bringBack())
        assertEquals(listOf("bring-back"), fake.commands)
    }
}
