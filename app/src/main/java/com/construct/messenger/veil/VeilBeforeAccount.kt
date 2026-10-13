package com.construct.messenger.veil

import com.construct.messenger.transport.TransportRouter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VEIL before there is an account (iOS `VeilBootstrapScanView`, `decisions/user-vouched-veil-bootstrap.md`).
 * The route machine starts with messaging (`MessagingRuntime.start`), which needs an account — so
 * a fresh install in a blocked network imported a front and still registered on the direct path,
 * which never answers. Started here instead once there is a front to use; then Auto escalates to
 * VEIL when the direct call fails, as it does for an account. Starting it twice is a no-op.
 *
 * Without a front nothing starts: someone who can reach the server never runs any of this
 * ("default-off in uncensored networks").
 */
@Singleton
class VeilBeforeAccount @Inject constructor(
    private val fronts: VeilFrontStore,
    private val router: dagger.Lazy<TransportRouter>,
) {
    fun startIfConfigured() {
        if (fronts.preferred() != null) router.get().start()
    }
}
