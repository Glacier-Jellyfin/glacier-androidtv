package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** Seeds in DolbyVisionRpuFuzzTestInputs/dolbyVisionRpu, fuzzing with JAZZER_FUZZ=1 (see CONTRIBUTING.md). */
class DolbyVisionRpuFuzzTest {

    @FuzzTest
    fun dolbyVisionRpu(data: FuzzedDataProvider) = DolbyVisionRpuFuzzer.fuzzerTestOneInput(data)

    /** Inputs the fuzzer once crashed on (DolbyVisionRpuFuzzTestInputs/regressions), so the fixes stay. */
    @FuzzTest
    fun regressions(data: FuzzedDataProvider) = DolbyVisionRpuFuzzer.fuzzerTestOneInput(data)
}
