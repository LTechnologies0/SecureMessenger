package ltechnologies.onionphone.securemessenger.protocol.matrix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixLoginFlowsTest {

    @Test
    fun emptyFlowsDoNotAssumePassword() {
        assertFalse(MatrixLoginFlows.supportsPassword(emptyList()))
        assertFalse(MatrixLoginFlows.supportsSso(emptyList()))
    }

    @Test
    fun passwordAndSsoAreDetectedFromFlowTypes() {
        assertTrue(MatrixLoginFlows.supportsPassword(listOf("m.login.password")))
        assertTrue(MatrixLoginFlows.supportsSso(listOf("m.login.sso")))
        assertTrue(MatrixLoginFlows.supportsSso(listOf("m.login.token")))
        assertFalse(MatrixLoginFlows.supportsPassword(listOf("m.login.sso")))
    }
}
