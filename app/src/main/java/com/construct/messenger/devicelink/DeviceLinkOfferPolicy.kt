package com.construct.messenger.devicelink

import com.construct.messenger.BuildConfig

/**
 * Whether this build offers linking a device, on either side of a link. **Canon:** iOS
 * `DeviceLinkOfferPolicy` (`DEBUG || INTERNAL_TOOLS`): debug builds and builds handed to testers
 * offer it; a build from `main` does not, until linking leaves testing.
 */
object DeviceLinkOfferPolicy {
    val isLinkingOffered: Boolean = BuildConfig.DEBUG || BuildConfig.INTERNAL_TOOLS
}
