package dev.dimension.flare.ui.presenter.compose

import dev.dimension.flare.data.repository.DebugRepository
import kotlin.native.HiddenFromObjC
import kotlin.uuid.Uuid

@HiddenFromObjC
public class ComposeDiagnosticTrace {
    private val id = Uuid.random().toString()

    public fun log(
        event: String,
        throwable: Throwable? = null,
    ) {
        DebugRepository.diagnostic("attempt=$id $event", throwable)
    }
}

@HiddenFromObjC
public fun logComposeDiagnostic(event: String) {
    DebugRepository.diagnostic(event)
}
