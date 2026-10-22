package com.hardwareshop.pos.data.remote

/** Background recovery may replay only jobs authored by this cloud identity. */
object RemotePrintRecoveryPolicy {
    fun mayReplay(authorUid: String, currentUid: String?) =
        authorUid.isNotBlank() && !currentUid.isNullOrBlank() && authorUid == currentUid

    fun isRegisteredPrinter(localDeviceId: String?, registeredDeviceId: String?) =
        !localDeviceId.isNullOrBlank() && localDeviceId == registeredDeviceId
}
