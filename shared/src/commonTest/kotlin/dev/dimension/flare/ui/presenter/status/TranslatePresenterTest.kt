package dev.dimension.flare.ui.presenter.status

import androidx.compose.runtime.BroadcastFrameClock
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.common.OnDeviceAI
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.data.network.ai.OpenAIService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatePresenterTest : RobolectricTest() {
    @Test
    fun savedProviderStartsOnlyOnceAfterSettingsHaveLoaded() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val root = createTestRootPath()
            val dataStore = AppDataStore(OkioFileStorage(createTestFileSystem(), root))
            var calls = 0
            val ai =
                AiCompletionService(
                    OpenAIService(),
                    object : OnDeviceAI {
                        override suspend fun isAvailable() = true

                        override suspend fun translate(
                            source: String,
                            targetLanguage: String,
                            prompt: String,
                        ): String? {
                            calls++
                            awaitCancellation()
                        }

                        override suspend fun tldr(
                            source: String,
                            targetLanguage: String,
                            prompt: String,
                        ): String? = error("Unused")
                    },
                )
            startKoin {
                modules(
                    module {
                        testSingle { dataStore }
                        testSingle { ai }
                    },
                )
            }
            val clock = BroadcastFrameClock()
            var collection: kotlinx.coroutines.Job? = null
            try {
                dataStore.appSettingsStore.updateData {
                    it.copy(
                        translateConfig = it.translateConfig.copy(provider = AppSettings.TranslateConfig.Provider.AI),
                        aiConfig = it.aiConfig.copy(type = AppSettings.AiConfig.Type.OnDevice),
                    )
                }
                val presenter = TranslatePresenter("Hello".toUiPlainText(), "zh-Hans")
                collection = launch(clock) { moleculeFlow(RecompositionMode.ContextClock) { presenter.body() }.collect {} }
                runCurrent()
                assertEquals(0, calls, "Do not start a request using the initial placeholder settings")
                clock.sendFrame(1L)
                runCurrent()
                assertEquals(1, calls)
                clock.sendFrame(2L)
                runCurrent()
                assertEquals(1, calls, "Applying the loaded settings must not restart the first request")
            } finally {
                collection?.cancelAndJoin()
                stopKoin()
                Dispatchers.resetMain()
                deleteTestRootPath(root)
            }
        }
}
