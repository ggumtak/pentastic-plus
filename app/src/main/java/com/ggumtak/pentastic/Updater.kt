package com.ggumtak.pentastic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * 앱 안에서 GitHub 최신 빌드를 확인하고 바로 설치한다.
 * 브라우저로 받으면 예전 파일을 잘못 누르기 쉬워서, 앱이 직접 받아 시스템 설치 화면을 띄운다.
 */
object Updater {
    private const val API = "https://api.github.com/repos/ggumtak/pentastic-plus/releases/latest"
    private val main = Handler(Looper.getMainLooper())

    class Latest(val build: Int, val url: String, val size: Long, val fingerprint: String?)

    fun installedBuild(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** 설치된 앱의 서명 지문 앞 16자리 (릴리스 설명의 '서명 지문'과 같으면 덮어쓰기 가능) */
    fun installedFingerprint(ctx: Context): String = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        if (cert == null) "?" else MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString("") { "%02x".format(it) }.take(16)
    } catch (_: Exception) {
        "?"
    }

    fun check(onResult: (Latest?, String?) -> Unit) {
        thread(name = "update-check") {
            try {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val build = Regex("#(\\d+)").find(json.optString("name"))?.groupValues?.get(1)?.toInt() ?: 0
                val fp = Regex("서명 지문: ([0-9a-f]+)").find(json.optString("body"))?.groupValues?.get(1)
                val assets = json.getJSONArray("assets")
                var url: String? = null
                var size = -1L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name") == "pentastic.apk") {
                        url = a.getString("browser_download_url")
                        size = a.optLong("size", -1)
                    }
                }
                val latest = url?.let { Latest(build, it, size, fp) }
                main.post { onResult(latest, if (latest == null) "릴리스에서 APK를 찾지 못했습니다." else null) }
            } catch (e: Exception) {
                main.post { onResult(null, "업데이트 확인 실패: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** 받아서 시스템 설치 화면을 띄운다. progress는 0~100. */
    fun downloadAndInstall(ctx: Context, latest: Latest, progress: (Int) -> Unit, onError: (String) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "update-download") {
            val installer = app.packageManager.packageInstaller
            var sessionId = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(app.packageName) }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = (URL(latest.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                    }
                    val total = if (latest.size > 0) latest.size else conn.contentLengthLong
                    conn.inputStream.use { input ->
                        session.openWrite("pentastic.apk", 0, total).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        main.post { progress(pct) }
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(app, InstallResultReceiver::class.java)
                    val pending = PendingIntent.getBroadcast(
                        app, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                if (sessionId >= 0) try {
                    installer.abandonSession(sessionId)
                } catch (_: Exception) {
                }
                main.post { onError("다운로드 실패: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }
}

/** 설치 진행 결과. 확인 화면이 필요하면 띄우고, 실패하면 이유를 알려 준다. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT,
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "서명이 달라 덮어쓸 수 없습니다. 이번 한 번만 펜스틱을 삭제한 뒤 설치해 주세요."
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "설치를 취소했습니다."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족합니다."
                    else -> "설치 실패: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}"
                }
                DebugLog.add("업데이트 설치 결과: $msg")
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
