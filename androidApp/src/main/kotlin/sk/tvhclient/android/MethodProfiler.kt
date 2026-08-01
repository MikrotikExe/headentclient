package sk.tvhclient.android

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/**
 * M455-diag: zaznam stromu volani priamo v appke (Debug.startMethodTracing).
 *
 * Android Studio Profiler potrebuje debuggovatelny build, ktory na starsich
 * zariadeniach padal (VerifyError). Tento zaznam funguje aj na release APK.
 * Vysledny .trace subor sa otvara v Android Studiu (File -> Open) a ukaze,
 * ktore metody kolko stoja.
 *
 * Docasna diagnostika — po vyrieseni HTSP zataze ide von.
 */
object MethodProfiler {
    private var running = false

    /**
     * M460-diag: naplanovany zaznam. Zapne sa v Diagnostike, ale sam sa spusti
     * az `delaySeconds` po navrate do prehravania — takze v profile nie je
     * prechod medzi obrazovkami (ten sam o sebe vyvola prekreslenie celeho UI
     * a skresluje vysledok), ale ustaleny stav pocas sledovania.
     */
    @Volatile
    var armed = false

    /** Vola PlayerActivity po nabehnuti prehravania. */
    fun startIfArmed(context: Context, delaySeconds: Int = 15, recordSeconds: Int = 10) {
        if (!armed || running) return
        armed = false
        Handler(Looper.getMainLooper()).postDelayed({
            record(context, recordSeconds)
        }, delaySeconds * 1000L)
    }

    /** Spusti zaznam na dany pocet sekund; subor skonci v files/profile.trace. */
    fun record(context: Context, seconds: Int = 10) {
        if (running) {
            Toast.makeText(context, "Záznam už beží", Toast.LENGTH_SHORT).show()
            return
        }
        // M455-fix: do verejneho priecinka appky — z filesDir sa subor na release
        // builde neda vytiahnut (run-as: package not debuggable). Tu staci
        // adb pull /sdcard/Android/data/sk.tvhclient/files/profile.trace
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, "profile.trace")
        runCatching {
            // 32 MB buffer, vzorkovanie kazdych 1000 us (1 ms) — nizsia rezia
            // nez plne trasovanie, staci na najdenie horucich metod.
            Debug.startMethodTracingSampling(out.absolutePath, 32 * 1024 * 1024, 1000)
            running = true
            Toast.makeText(context, "Záznam spustený na ${seconds}s", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { Debug.stopMethodTracing() }
                running = false
                Toast.makeText(
                    context, "Záznam uložený: ${out.absolutePath}", Toast.LENGTH_LONG
                ).show()
            }, seconds * 1000L)
        }.onFailure {
            running = false
            Toast.makeText(context, "Záznam zlyhal: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
