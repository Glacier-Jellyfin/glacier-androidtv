package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** Seeds in NameOrderFuzzTestInputs/nameOrder, fuzzing with JAZZER_FUZZ=1 (see CONTRIBUTING.md). */
class NameOrderFuzzTest {

    @FuzzTest
    fun nameOrder(data: FuzzedDataProvider) = NameOrderFuzzer.fuzzerTestOneInput(data)
}
