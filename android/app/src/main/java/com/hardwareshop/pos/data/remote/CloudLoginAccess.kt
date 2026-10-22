package com.hardwareshop.pos.data.remote

/** An email login may open an existing local shop only after server membership checks. */
internal object CloudLoginAccess {
    fun checkSignIn(hasLocalOwner: Boolean, hasShopLink: Boolean, locallyAuthenticatedOwner: Boolean) {
        check(!hasLocalOwner || hasShopLink || locallyAuthenticatedOwner) {
            "This phone has an old local shop setup. Use Start fresh on this phone to clear it, then sign in with your email and password."
        }
    }

    fun checkCreate(hasLocalOwner: Boolean, hasShopLink: Boolean) {
        check(!hasLocalOwner && !hasShopLink) {
            "Use Sign in for your existing shop, or Start fresh on this phone before creating a new shop account."
        }
    }
}
