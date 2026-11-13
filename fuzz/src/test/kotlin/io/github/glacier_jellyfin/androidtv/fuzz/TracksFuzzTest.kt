package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** Seeds in TracksFuzzTestInputs/tracks, fuzzing with JAZZER_FUZZ=1 (see CONTRIBUTING.md). */
class TracksFuzzTest {

    @FuzzTest
    fun tracks(data: FuzzedDataProvider) = TracksFuzzer.fuzzerTestOneInput(data)

    /** Inputs the fuzzer once crashed on (TracksFuzzTestInputs/regressions), so the fixes stay. */
    @FuzzTest
    fun regressions(data: FuzzedDataProvider) = TracksFuzzer.fuzzerTestOneInput(data)
}
