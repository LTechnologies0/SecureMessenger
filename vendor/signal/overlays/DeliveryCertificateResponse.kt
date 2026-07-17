package org.whispersystems.signalservice.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

/** Response body for GET /v1/certificate/delivery */
internal class DeliveryCertificateResponse(
    @JsonProperty("certificate") val certificate: ByteArray? = null,
)
