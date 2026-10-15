package com.mrlaki5.mystockmanager.ui.events

import com.mrlaki5.mystockmanager.generation.GenerationProvider.ON_DEVICE
import com.mrlaki5.mystockmanager.generation.GenerationProvider.OPENAI
import com.mrlaki5.mystockmanager.ondevice.ModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupStepTest {

    @Test
    fun `no guide once the chosen provider can generate`() {
        assertNull(SetupStep.of(ON_DEVICE, hasApiKey = false, model = ModelState.Ready))
        assertNull(SetupStep.of(OPENAI, hasApiKey = true, model = ModelState.Missing))
    }

    @Test
    fun `a fresh install is asked to choose`() {
        assertEquals(SetupStep.CHOOSE_PROVIDER, SetupStep.of(ON_DEVICE, hasApiKey = false, model = ModelState.Missing))
        assertEquals(SetupStep.CHOOSE_PROVIDER, SetupStep.of(ON_DEVICE, hasApiKey = false, model = ModelState.Failed("x")))
    }

    @Test
    fun `an api key does not count while on-device is selected`() {
        assertEquals(SetupStep.CHOOSE_PROVIDER, SetupStep.of(ON_DEVICE, hasApiKey = true, model = ModelState.Missing))
    }

    @Test
    fun `download in flight and missing key and unsupported phone get their own step`() {
        assertEquals(SetupStep.MODEL_DOWNLOADING, SetupStep.of(ON_DEVICE, false, ModelState.Downloading(1, 2, false)))
        assertEquals(SetupStep.MODEL_DOWNLOADING, SetupStep.of(ON_DEVICE, false, ModelState.Verifying))
        assertEquals(SetupStep.OPENAI_KEY_MISSING, SetupStep.of(OPENAI, false, ModelState.Ready))
        assertEquals(SetupStep.ON_DEVICE_UNSUPPORTED, SetupStep.of(ON_DEVICE, true, ModelState.Unsupported))
    }
}
