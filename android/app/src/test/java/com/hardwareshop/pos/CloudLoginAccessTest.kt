package com.hardwareshop.pos

import com.hardwareshop.pos.data.remote.CloudLoginAccess
import org.junit.Assert.*
import org.junit.Test

class CloudLoginAccessTest {
    @Test fun freshInstallationCanSignInUsingEmailWithoutLocalApproval() {
        CloudLoginAccess.checkSignIn(false, false, false)
    }
    @Test fun linkedShopCanReauthenticateWithoutASecondPassword() {
        CloudLoginAccess.checkSignIn(true, true, false)
    }
    @Test fun unrelatedCloudLoginCannotAccessAnUnlinkedLocalShop() {
        try { CloudLoginAccess.checkSignIn(true, false, false); fail("Must reset old local data") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Start fresh on this phone")) }
    }
    @Test fun alreadyAuthenticatedOwnerRetainsExistingAuthorisedSetup() {
        CloudLoginAccess.checkSignIn(true, false, true)
    }
    @Test fun creationRequiresAnEmptyLocalInstallation() {
        CloudLoginAccess.checkCreate(false, false)
        for ((owner, linked) in listOf(true to false, false to true, true to true)) {
            try { CloudLoginAccess.checkCreate(owner, linked); fail("Must not overwrite an existing setup") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Start fresh")) }
        }
    }
}
