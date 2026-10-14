package com.construct.messenger.data.repository

/**
 * The server answered, and the answer was "no" (`success = false`). Not a connection problem:
 * the UI says "the server could not do it" ([com.construct.messenger.ui.UserError.SERVER_FAILED]).
 * [message] is the server's own text and belongs in the log only.
 */
class ServerRefusedException(message: String) : Exception(message)
