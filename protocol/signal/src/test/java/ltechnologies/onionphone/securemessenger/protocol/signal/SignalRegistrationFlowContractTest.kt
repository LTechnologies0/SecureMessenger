package ltechnologies.onionphone.securemessenger.protocol.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the add-account Signal flow against registration outcome mapping bugs
 * that blocked CAPTCHA → SMS progression (failures must not look like SMS steps).
 */
class SignalRegistrationFlowContractTest {

    @Test
    fun failureStepCarriesReasonAndIsNotSmsPrompt() {
        val failed = SignalRegistrationStep.Failed("Network error")
        assertEquals("Network error", failed.reason)
        assertNotEquals(SignalRegistrationStep.SmsCodeRequired, failed)
        assertNotEquals(SignalRegistrationStep.CaptchaRequired, failed)
    }

    @Test
    fun addAccountWizardStepsAreDistinct() {
        val steps = setOf(
            SignalRegistrationStep.CaptchaRequired,
            SignalRegistrationStep.RequestSms,
            SignalRegistrationStep.SmsCodeRequired,
            SignalRegistrationStep.PinRequired,
            SignalRegistrationStep.Complete,
            SignalRegistrationStep.Failed("x"),
        )
        assertEquals(6, steps.size)
    }
}
