package com.promulticam.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.time.Instant

/* The same account as the web app (Firebase): log in, register, Pro status from the same database */
object Auth {
    private const val KEY = "AIzaSyDIncQ3Nt6v6_WbMCs42t81DZVg6f35Hwc"   // Firebase web key (public by design, same as the web app)
    private const val PROJECT = "kukusanmy"
    private const val DB = "promulticam"
    private const val REGION = "asia-southeast1"
    const val SUPER = "D3U6Dxs5joZQq5EYRdjZQUmRV773"
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ctx: Context
    private val sp get() = ctx.getSharedPreferences("pmc_auth", Context.MODE_PRIVATE)

    var uid: String? = null; private set
    var email: String? = null; private set
    private var idToken: String? = null; private var refresh: String? = null; private var exp = 0L
    var expiry = 0L; private set
    var plan = "free"; private set
    var status = "active"; private set

    fun init(c: Context) {
        ctx = c.applicationContext
        uid = sp.getString("uid", null); email = sp.getString("email", null); idToken = sp.getString("id", null); refresh = sp.getString("rt", null); exp = sp.getLong("exp", 0)
        expiry = sp.getLong("expiry", 0); plan = sp.getString("plan", "free") ?: "free"; status = sp.getString("status", "active") ?: "active"
    }
    val signedIn get() = uid != null
    val isAdmin get() = uid == SUPER
    val isPro get() = isAdmin || (status != "suspended" && expiry > System.currentTimeMillis())
    val daysLeft get() = maxOf(0L, (expiry - System.currentTimeMillis() + 86_399_999) / 86_400_000).toInt()

    private fun post(url: String, body: JSONObject, auth: String? = null): JSONObject {
        val rb = Request.Builder().url(url).post(body.toString().toRequestBody(json))
        if (auth != null) rb.header("Authorization", "Bearer $auth")
        http.newCall(rb.build()).execute().use { r -> val s = r.body?.string() ?: "{}"; return JSONObject(s) }
    }
    private fun bg(work: () -> String?, done: (String?) -> Unit) { Thread { val e = try { work() } catch (x: Exception) { "NETWORK" }; main.post { done(e) } }.start() }

    private fun save(j: JSONObject) {
        uid = j.optString("localId"); email = j.optString("email"); idToken = j.optString("idToken"); refresh = j.optString("refreshToken")
        exp = System.currentTimeMillis() + (j.optString("expiresIn", "3600").toLong() - 60) * 1000
        sp.edit().putString("uid", uid).putString("email", email).putString("id", idToken).putString("rt", refresh).putLong("exp", exp).apply()
    }

    fun login(em: String, pw: String, done: (String?) -> Unit) = bg({
        val j = post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$KEY", JSONObject().put("email", em).put("password", pw).put("returnSecureToken", true))
        j.optJSONObject("error")?.let { return@bg it.optString("message") }
        save(j); loadProfileSync(); null
    }, done)

    fun register(em: String, pw: String, done: (String?) -> Unit) = bg({
        val j = post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$KEY", JSONObject().put("email", em).put("password", pw).put("returnSecureToken", true))
        j.optJSONObject("error")?.let { return@bg it.optString("message") }
        save(j)
        try { post("https://$REGION-$PROJECT.cloudfunctions.net/pmc_initUser", JSONObject().put("data", JSONObject()), idToken) } catch (e: Exception) {}
        loadProfileSync(); null
    }, done)

    /* Google: the phone's Google account picker, then the same Firebase account as the web */
    private const val WEB_CLIENT = "710803283811-uep9lfn344g3uk4on3otln85kdh90pab.apps.googleusercontent.com"
    fun google(a: Activity, done: (String?) -> Unit) {
        val req = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(WEB_CLIENT).build()).build()
        CredentialManager.create(a).getCredentialAsync(a, req, null, ContextCompat.getMainExecutor(a),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(r: GetCredentialResponse) {
                    val c = r.credential
                    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val tok = try { GoogleIdTokenCredential.createFrom(c.data).idToken } catch (e: Exception) { null }
                        if (tok == null) { done("GOOGLE"); return }
                        bg({
                            val j = post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$KEY", JSONObject()
                                .put("postBody", "id_token=$tok&providerId=google.com").put("requestUri", "http://localhost")
                                .put("returnIdpCredential", true).put("returnSecureToken", true))
                            j.optJSONObject("error")?.let { return@bg it.optString("message") }
                            save(j)
                            if (j.optBoolean("isNewUser")) try { post("https://$REGION-$PROJECT.cloudfunctions.net/pmc_initUser", JSONObject().put("data", JSONObject()), idToken) } catch (e: Exception) {}
                            loadProfileSync(); null
                        }, done)
                    } else done("GOOGLE")
                }
                override fun onError(e: GetCredentialException) { done(if (e is GetCredentialCancellationException) "CANCEL" else "GOOGLE:" + (e.message ?: e.type)) }
            })
    }

    fun reset(em: String, done: (String?) -> Unit) = bg({
        val j = post("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$KEY", JSONObject().put("requestType", "PASSWORD_RESET").put("email", em))
        j.optJSONObject("error")?.optString("message")
    }, done)

    fun logout() { uid = null; email = null; idToken = null; refresh = null; expiry = 0; plan = "free"; sp.edit().clear().apply() }

    private fun tokenSync(): String? {
        if (idToken != null && System.currentTimeMillis() < exp) return idToken
        val rt = refresh ?: return null
        val r = Request.Builder().url("https://securetoken.googleapis.com/v1/token?key=$KEY")
            .post("grant_type=refresh_token&refresh_token=$rt".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        http.newCall(r).execute().use { res ->
            val j = JSONObject(res.body?.string() ?: "{}")
            if (j.has("id_token")) { idToken = j.getString("id_token"); refresh = j.optString("refresh_token", rt); exp = System.currentTimeMillis() + (j.optString("expires_in", "3600").toLong() - 60) * 1000
                sp.edit().putString("id", idToken).putString("rt", refresh).putLong("exp", exp).apply() }
        }
        return idToken
    }

    private fun loadProfileSync() {
        val u = uid ?: return; val tk = tokenSync() ?: return
        val r = Request.Builder().url("https://firestore.googleapis.com/v1/projects/$PROJECT/databases/$DB/documents/users/$u").header("Authorization", "Bearer $tk").build()
        http.newCall(r).execute().use { res ->
            val f = JSONObject(res.body?.string() ?: "{}").optJSONObject("fields") ?: return
            f.optJSONObject("expiry")?.optString("timestampValue")?.let { ts -> expiry = try { Instant.parse(ts).toEpochMilli() } catch (e: Exception) { 0 } }
            plan = f.optJSONObject("plan")?.optString("stringValue") ?: "free"
            status = f.optJSONObject("status")?.optString("stringValue") ?: "active"
            sp.edit().putLong("expiry", expiry).putString("plan", plan).putString("status", status).apply()
        }
    }
    fun refreshProfile(done: () -> Unit) { if (!signedIn) { done(); return }; bg({ loadProfileSync(); null }) { done() } }

    fun errText(code: String?): String = when {
        code == null -> ""
        code.contains("EMAIL_EXISTS") -> Ui.t("This email already has an account — log in instead", "Emel ini sudah ada akaun — log masuk")
        code.contains("INVALID_IDP") || code.contains("OPERATION_NOT_ALLOWED") -> Ui.t("Google sign-in refused: ", "Log masuk Google ditolak: ") + code
        code.contains("INVALID") || code.contains("EMAIL_NOT_FOUND") -> Ui.t("Wrong email or password", "Emel atau kata laluan salah")
        code.contains("WEAK_PASSWORD") -> Ui.t("Password needs 6 characters or more", "Kata laluan perlu 6 aksara atau lebih")
        code.contains("TOO_MANY") -> Ui.t("Too many tries — wait a moment", "Terlalu banyak cubaan — tunggu sebentar")
        code == "CANCEL" -> ""
        code.startsWith("GOOGLE") -> Ui.t("Google sign-in did not work: ", "Log masuk Google gagal: ") + code.removePrefix("GOOGLE").removePrefix(":")
        code == "NETWORK" -> Ui.t("No internet connection", "Tiada sambungan internet")
        else -> code
    }
}
