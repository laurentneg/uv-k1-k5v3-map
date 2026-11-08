package com.uvk1.map

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.uvk1.map.databinding.ActivityLogsBinding

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val refresh: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false
        binding.logsHeader.applySystemBarInsets(top = true, bottom = false, horizontal = true)
        binding.logScroll.applySystemBarInsets(top = false, bottom = true, horizontal = false)

        binding.backButton.setOnClickListener { finish() }
        binding.clearLogsButton.setOnClickListener {
            SessionLog.clear()
            render()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        SessionLog.addListener(refresh)
        render()
    }

    override fun onStop() {
        SessionLog.removeListener(refresh)
        super.onStop()
    }

    private fun render() {
        val targets = SessionLog.targetsText()
        binding.targetList.text = targets.ifBlank { getString(R.string.no_targets) }
        val logs = SessionLog.statusText()
        binding.logList.text = logs.ifBlank { getString(R.string.no_logs) }
    }
}
