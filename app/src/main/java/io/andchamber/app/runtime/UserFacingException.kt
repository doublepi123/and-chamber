package io.andchamber.app.runtime

/**
 * A failure whose headline the user should see, translated. [message] stays English and technical (it
 * goes to logs and to [RuntimeState.Failed.reason]); [key] selects the localised headline; [detail] is
 * raw technical output, shown untranslated.
 */
class UserFacingException(
    val key: MessageKey,
    message: String,
    val detail: String? = null,
) : IllegalStateException(message)
