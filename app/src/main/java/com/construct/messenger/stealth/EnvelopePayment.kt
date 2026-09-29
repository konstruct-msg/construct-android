package com.construct.messenger.stealth

/**
 * What a sealed envelope pays with. `decisions/contact-traffic-is-vouched-not-purchased.md`:
 * a credential the recipient issued owes no token; a credential the server has just refused is
 * not offered again, and that envelope pays — even where the per-stream policy would not, because
 * under enforce an envelope with neither is refused.
 */
enum class EnvelopePayment {
    CREDENTIAL, TOKEN, NOTHING;

    companion object {
        fun choose(credential: Boolean, afterCredentialRejection: Boolean, policyWantsToken: Boolean): EnvelopePayment =
            when {
                afterCredentialRejection -> TOKEN
                credential -> CREDENTIAL
                policyWantsToken -> TOKEN
                else -> NOTHING
            }
    }
}
