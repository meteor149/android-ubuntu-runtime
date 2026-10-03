package ai.meteor.ubuntu.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import ai.meteor.ubuntu.runtime.UbuntuCommand
import ai.meteor.ubuntu.runtime.UbuntuEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A standalone Maven consumer: no dependency on the DSH app or shared UI module. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Installing Ubuntu…"; setPadding(24, 24, 24, 24) }
        setContentView(status)
        scope.launch {
            try {
                val ubuntu = UbuntuEnvironment(applicationContext)
                ubuntu.install()
                val result = ubuntu.execute(UbuntuCommand(listOf("/bin/bash", "-lc",
                    "cat /etc/os-release; uname -m; printf '%s\\n' \"\$LIBRARY_TEST\""),
                    mapOf("LIBRARY_TEST" to "maven-consumer-ok")))
                check(result.exitCode == 0) { "Ubuntu exited with ${result.exitCode}: ${result.output}" }
                check("maven-consumer-ok" in result.output) { "Guest environment was not preserved" }
                status.text = result.output
                Log.i("UbuntuLibrarySample", "SUCCESS\n${result.output}")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                status.text = error.stackTraceToString()
                Log.e("UbuntuLibrarySample", "FAILED", error)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
