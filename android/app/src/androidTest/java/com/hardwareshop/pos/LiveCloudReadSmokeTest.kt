package com.hardwareshop.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.hardwareshop.pos.data.sync.FirestoreSyncTransport
import com.hardwareshop.pos.data.sync.SyncProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, read-only live check. Credentials are supplied at execution, never stored here. */
@RunWith(AndroidJUnit4::class)
class LiveCloudReadSmokeTest {
    @Test fun existingOwnerCanReadAllCloudSyncStreams() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val email = args.getString("cloudEmail")
        val password = args.getString("cloudPassword")
        assumeTrue("Live credentials must be supplied explicitly", email != null && password != null)
        val auth = FirebaseAuth.getInstance()
        val cloud = FirebaseFirestore.getInstance()
        FirebaseFirestore.setLoggingEnabled(true)
        try {
            withTimeout(90_000) {
                val user = checkNotNull(auth.signInWithEmailAndPassword(email!!, password!!).await().user)
                println("LIVE CLOUD: email/password authenticated")
                val token = checkNotNull(user.getIdToken(false).await().token)
                val project = com.google.firebase.FirebaseApp.getInstance().options.projectId
                val url = java.net.URL("https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/cloudAccounts/${user.uid}")
                val connection = url.openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    assertEquals("Android HTTPS must read the same shop profile", 200, connection.responseCode)
                    println("LIVE CLOUD: Android HTTPS shop profile readable")
                } finally { connection.disconnect() }
                var profile: com.google.firebase.firestore.DocumentSnapshot? = null
                repeat(4) { attempt ->
                    if (profile == null) try {
                        profile = cloud.collection("cloudAccounts").document(user.uid).get(Source.SERVER).await()
                    } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                        if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE || attempt == 3) throw e
                        kotlinx.coroutines.delay(10_000)
                    }
                }
                val shop = checkNotNull(checkNotNull(profile).getString("shopId"))
                println("LIVE CLOUD: shop profile read from server")
                val transport = FirestoreSyncTransport()
                val member = transport.verifyMembership(shop)
                assertEquals(SyncProtocol.Role.OWNER, member.actor.role)
                println("LIVE CLOUD: enabled OWNER membership verified")
                for (scope in SyncProtocol.Scope.entries) {
                    transport.fetchPage(member, scope, limit = 1)
                    transport.fetchBundlePage(member, scope, limit = 1)
                    println("LIVE CLOUD: $scope changes and bundles readable")
                }
            }
        } finally {
            FirebaseFirestore.setLoggingEnabled(false)
            auth.signOut()
        }
    }
}
