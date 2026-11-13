package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** Seeds in Iec61937FuzzTestInputs/iec61937, fuzzing with JAZZER_FUZZ=1 (see CONTRIBUTING.md). */
class Iec61937FuzzTest {

    @FuzzTest
    fun iec61937(data: FuzzedDataProvider) = Iec61937Fuzzer.fuzzerTestOneInput(data)

    /** Inputs the fuzzer once crashed on (Iec61937FuzzTestInputs/regressions), so the fixes stay. */
    @FuzzTest
    fun regressions(data: FuzzedDataProvider) = Iec61937Fuzzer.fuzzerTestOneInput(data)
}
