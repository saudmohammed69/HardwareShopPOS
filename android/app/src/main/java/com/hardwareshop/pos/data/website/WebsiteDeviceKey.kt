package com.hardwareshop.pos.data.website

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** A separate non-exportable installation key. Printer ANDROID_ID and existing
 * Firebase credentials are never used as proof of website device authority. */
class WebsiteDeviceKey(val alias:String="hardwareshop.website.installation.v1") {
    init { require(alias.startsWith("hardwareshop.website.")&&alias.length<=150) }
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    @Synchronized fun ensureCreated() {
        if(store().containsAlias(alias))return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }
    fun exists()=store().containsAlias(alias)
    fun publicJwk():JsonObject {
        val key=store().getCertificate(alias)?.publicKey as? ECPublicKey
            ?: error("Website installation key is unavailable; request new owner approval")
        fun coordinate(value:BigInteger):String {
            val bytes=value.toByteArray().let{if(it.size==33&&it[0]==0.toByte())it.copyOfRange(1,33)else it}
            require(bytes.size<=32);return encode(ByteArray(32-bytes.size)+bytes)
        }
        return JsonObject().apply{addProperty("kty","EC");addProperty("crv","P-256");addProperty("x",coordinate(key.w.affineX));addProperty("y",coordinate(key.w.affineY))}
    }
    fun fingerprint():String {
        val k=publicJwk();val canonical="{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${k["x"].asString}\",\"y\":\"${k["y"].asString}\"}"
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }
    fun sign(message:String):String {
        val key=store().getKey(alias,null) as? java.security.PrivateKey
            ?: error("Website installation key is unavailable; request new owner approval")
        val der=Signature.getInstance("SHA256withECDSA").apply{initSign(key);update(message.toByteArray(Charsets.UTF_8))}.sign()
        return encode(derToP1363(der))
    }
    fun deleteForLocalTest(){require(alias.contains(".test."));store().deleteEntry(alias)}
    companion object {
        fun encode(bytes:ByteArray):String=Base64.encodeToString(bytes,Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun proofMessage(method:String,url:String,body:ByteArray,idToken:String,device:String,keyVersion:Int,time:Long,nonce:String):String {
            require(method in listOf("GET","POST")&&keyVersion>0&&'\n' !in url&&'\n' !in device)
            return listOf("HWPOS-DEVICE-V1",method,url,sha256(body),sha256(idToken.toByteArray(Charsets.UTF_8)),device,keyVersion.toString(),time.toString(),nonce).joinToString("\n")
        }
        internal fun derToP1363(der:ByteArray):ByteArray {
            require(der.size in 8..72&&der[0]==0x30.toByte()&&(der[1].toInt() and 255)==der.size-2)
            var offset=2
            fun scalar():ByteArray {
                require(der[offset++]==2.toByte());val count=der[offset++].toInt() and 255
                require(count in 1..33&&offset+count<=der.size)
                val value=der.copyOfRange(offset,offset+count);offset+=count
                val bytes=if(value.size==33){require(value[0]==0.toByte());value.copyOfRange(1,33)}else value
                return ByteArray(32-bytes.size)+bytes
            }
            val result=scalar()+scalar();require(offset==der.size);return result
        }
    }
}
