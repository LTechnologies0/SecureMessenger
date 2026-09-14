package ltechnologies.onionphone.securemessenger.protocol.signal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.api.link.TransferArchiveResponse

class SignalLinkAndSyncPollTest {

    @Test
    fun http404MeansNotReadyYet() {
        assertEquals(
            TransferArchivePollDecision.RETRY,
            SignalLinkAndSync.decidePoll(httpCode = 404),
        )
    }

    @Test
    fun http204MeansLongPollTimedOut() {
        assertEquals(
            TransferArchivePollDecision.RETRY,
            SignalLinkAndSync.decidePoll(httpCode = 204),
        )
    }

    @Test
    fun empty200MeansNotReadyYet() {
        assertEquals(
            TransferArchivePollDecision.RETRY,
            SignalLinkAndSync.decidePoll(
                httpCode = 200,
                response = TransferArchiveResponse(),
            ),
        )
    }

    @Test
    fun archiveLocationMeansDownload() {
        assertEquals(
            TransferArchivePollDecision.DOWNLOAD,
            SignalLinkAndSync.decidePoll(
                httpCode = 200,
                response = TransferArchiveResponse(cdn = 3, key = "abc"),
            ),
        )
    }

    @Test
    fun primaryAbortMeansContinueWithoutHistory() {
        assertEquals(
            TransferArchivePollDecision.CONTINUE_WITHOUT,
            SignalLinkAndSync.decidePoll(
                httpCode = 200,
                response = TransferArchiveResponse(
                    error = TransferArchiveResponse.ERROR_CONTINUE_WITHOUT_UPLOAD,
                ),
            ),
        )
    }

    @Test
    fun relinkRequestedIsTerminal() {
        assertEquals(
            TransferArchivePollDecision.RELINK,
            SignalLinkAndSync.decidePoll(
                httpCode = 200,
                response = TransferArchiveResponse(
                    error = TransferArchiveResponse.ERROR_RELINK_REQUESTED,
                ),
            ),
        )
    }

    @Test
    fun networkBlipRetries() {
        assertEquals(
            TransferArchivePollDecision.RETRY,
            SignalLinkAndSync.decidePoll(networkError = true),
        )
    }

    @Test
    fun serverErrorRetries() {
        assertEquals(
            TransferArchivePollDecision.RETRY,
            SignalLinkAndSync.decidePoll(httpCode = 503),
        )
    }

    @Test
    fun http400MeansGiveUpWithoutArchive() {
        assertEquals(
            TransferArchivePollDecision.CONTINUE_WITHOUT,
            SignalLinkAndSync.decidePoll(httpCode = 400),
        )
    }

    @Test
    fun http401RetriesLikeOfficialClient() {
        assertEquals(
            TransferArchivePollDecision.RETRY,
            SignalLinkAndSync.decidePoll(httpCode = 401),
        )
    }

    @Test
    fun applicationErrorMeansContinueWithout() {
        assertEquals(
            TransferArchivePollDecision.CONTINUE_WITHOUT,
            SignalLinkAndSync.decidePoll(applicationError = true),
        )
    }
}
