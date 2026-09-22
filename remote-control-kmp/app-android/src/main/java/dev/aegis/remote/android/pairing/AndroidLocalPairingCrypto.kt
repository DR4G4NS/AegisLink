package dev.aegis.remote.android.pairing

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AndroidLocalPairingCrypto {
    fun derive(
        secret: String,
        payload: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }
}
