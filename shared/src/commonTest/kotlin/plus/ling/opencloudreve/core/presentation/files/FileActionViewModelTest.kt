package plus.ling.opencloudreve.core.presentation.files

import app.cash.turbine.test
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.json
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.files.okData
import plus.ling.opencloudreve.core.presentation.ErrorKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileActionViewModelTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun lockConflictThenForceUnlockAndRetry() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var deletes = 0
        val env = DataEnv { req ->
            when {
                req.apiPath == "/file" && req.method == HttpMethod.Delete -> {
                    deletes++
                    if (deletes == 1) {
                        err(
                            40073,
                            "Lock conflict",
                            """[{"path":"/a.txt","token":"opaquelocktoken:1","type":0,"owner":{"application":{"type":"webdav"}}}]""",
                        )
                    } else {
                        okData()
                    }
                }
                req.apiPath == "/file/lock" -> okData()
                else -> err(404)
            }
        }
        val vm = FileActionViewModel(ACCOUNT, env.files)
        val op = FileOperation.Delete(listOf("cloudreve://my/a.txt"))

        vm.effects.test {
            vm.on(FileActionIntent.Run(op))
            val prompt = assertNotNull(vm.state.first { it.lockConflict != null }.lockConflict)
            assertEquals(op, prompt.operation)
            assertEquals("opaquelocktoken:1", prompt.conflicts.single().token)
            assertEquals("webdav", prompt.conflicts.single().owner?.application?.type)
            assertNull(vm.state.value.running)

            vm.on(FileActionIntent.ForceUnlockAndRetry)
            val done = assertIs<FileActionEffect.Succeeded>(awaitItem())
            assertEquals(op, done.operation)
            assertIs<FileOperationOutcome.Done>(done.outcome)
        }
        assertNull(vm.state.value.lockConflict)
        assertNull(vm.state.value.running)

        val paths = env.server.requests.map { it.method.value + " " + it.apiPath }
        assertEquals(listOf("DELETE /file", "DELETE /file/lock", "DELETE /file"), paths)
        assertEquals("""{"tokens":["opaquelocktoken:1"]}""", env.server.requests[1].bodyText())
        vm.dispose()
    }

    @Test
    fun dismissConflictDropsPendingOperation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val env = DataEnv { err(40073, "Lock conflict", """[{"path":"/a","token":"t"}]""") }
        val vm = FileActionViewModel(ACCOUNT, env.files)
        vm.on(FileActionIntent.Run(FileOperation.Rename("cloudreve://my/a", "b")))
        vm.state.first { it.lockConflict != null }
        vm.on(FileActionIntent.DismissConflict)
        assertNull(vm.state.value.lockConflict)
        vm.on(FileActionIntent.ForceUnlockAndRetry)
        assertEquals(1, env.server.requests.size)
        vm.dispose()
    }

    @Test
    fun partialFailureCollectsAggregatedErrors() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val env = DataEnv {
            json("""{"code":40081,"msg":"Batch failed","aggregated_error":{"cloudreve://my/b":{"code":40044,"msg":"not found"}}}""")
        }
        val vm = FileActionViewModel(ACCOUNT, env.files)
        val op = FileOperation.Move(listOf("cloudreve://my/a", "cloudreve://my/b"), "cloudreve://my/dst")
        vm.effects.test {
            vm.on(FileActionIntent.Run(op))
            val failed = assertIs<FileActionEffect.Failed>(awaitItem())
            assertEquals(ErrorKind.PartialFailure, failed.error.kind)
        }
        val failures = vm.state.value.partialFailures
        assertEquals(1, failures.size)
        assertEquals("cloudreve://my/b", failures.single().uri)
        assertEquals(ErrorKind.NotFound, failures.single().error.kind)
        vm.on(FileActionIntent.DismissFailures)
        assertTrue(vm.state.value.partialFailures.isEmpty())
        vm.dispose()
    }
}
