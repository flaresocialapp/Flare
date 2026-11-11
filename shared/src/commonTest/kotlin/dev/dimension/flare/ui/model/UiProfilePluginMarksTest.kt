package dev.dimension.flare.ui.model

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

class UiProfilePluginMarksTest {
    @Test
    fun missingMarksArePreservedAndExplicitlyClearedMarksAreRemoved() {
        val existing =
            createSampleUser().copy(
                pluginId = "test.plugin",
                mark = persistentListOf(UiProfile.Mark.Verified, UiProfile.Mark.Cat),
            )
        val partial = existing.copy(mark = persistentListOf())
        assertEquals(existing.mark, partial.mergeWith(existing).mark)
        val revoked = partial.copy(clearedMarks = persistentListOf(UiProfile.Mark.Verified)).mergeWith(existing)
        assertEquals(listOf(UiProfile.Mark.Cat), revoked.mark)
        assertEquals(revoked.mark, partial.mergeWith(revoked).mark)
    }
}
