package io.andchamber.app.runtime.install

/** Recognises apk/npm output that means the package mirrors could not be reached. */
object NetworkFailure {
    const val MESSAGE =
        "This device can't reach the package mirrors (Alpine, npm). " +
            "Check the network or VPN, then try again."

    private val markers =
        listOf(
            "DNS: transient error",
            "bad address",
            "EAI_AGAIN",
            "ENOTFOUND",
            "ETIMEDOUT",
            "Operation not permitted",
        )

    fun isNetworkOutput(line: String): Boolean = markers.any(line::contains)
}
