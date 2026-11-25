package com.wififtp.server

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.DynamicColors
import com.wififtp.server.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private var lastState: ServerState = ServerState.Stopped
    private var currentAddresses: List<String> = emptyList()

    // Notification permission (Android 13+). The server starts either way.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startServerNow() }

    // Android 10: WRITE_EXTERNAL_STORAGE
    private val legacyStorageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateStorageUi() }

    // Android 11+: "All files access" settings screen
    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateStorageUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        settings = AppSettings(this)
        binding.portInput.setText(settings.port.toString())
        binding.userInput.setText(settings.username)
        binding.passwordInput.setText(settings.password)
        binding.folderInput.setText(settings.homePath)
        binding.readOnlySwitch.isChecked = settings.readOnly

        binding.startStopButton.setOnClickListener { onStartStopClicked() }
        binding.storageButton.setOnClickListener { requestStorageAccess() }
        binding.copyButton.setOnClickListener { copyAddress() }
        binding.batteryButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FtpServerState.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStorageUi()
        render(lastState) // refresh IP addresses (network may have changed)
    }

    // ------------------------------------------------------------------ UI

    private fun render(state: ServerState) {
        lastState = state
        val editable = state is ServerState.Stopped || state is ServerState.Error

        binding.statusText.text = when (state) {
            is ServerState.Stopped -> getString(R.string.status_stopped)
            is ServerState.Starting -> getString(R.string.status_starting)
            is ServerState.Running -> getString(R.string.status_running)
            is ServerState.Error -> getString(R.string.status_error, state.message)
        }

        if (state is ServerState.Running) {
            currentAddresses = NetworkUtils.localIpv4Addresses(this)
                .map { "ftp://$it:${state.port}" }
            binding.addressesText.text =
                if (currentAddresses.isEmpty()) getString(R.string.no_network)
                else getString(R.string.connect_to) + "\n" + currentAddresses.joinToString("\n")
            binding.addressesText.visibility = android.view.View.VISIBLE
            binding.copyButton.visibility =
                if (currentAddresses.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        } else {
            currentAddresses = emptyList()
            binding.addressesText.visibility = android.view.View.GONE
            binding.copyButton.visibility = android.view.View.GONE
        }

        binding.startStopButton.text = getString(
            if (state is ServerState.Running) R.string.btn_stop else R.string.btn_start
        )
        binding.startStopButton.isEnabled = state !is ServerState.Starting

        binding.portLayout.isEnabled = editable
        binding.userLayout.isEnabled = editable
        binding.passwordLayout.isEnabled = editable
        binding.folderLayout.isEnabled = editable
        binding.readOnlySwitch.isEnabled = editable
    }

    private fun updateStorageUi() {
        binding.storageCard.visibility =
            if (StorageAccess.hasAccess(this)) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun copyAddress() {
        val text = currentAddresses.firstOrNull() ?: return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("ftp", text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------- start / stop

    private fun onStartStopClicked() {
        when (FtpServerState.state.value) {
            is ServerState.Running, is ServerState.Starting ->
                stopService(Intent(this, FtpService::class.java))
            else -> tryStart()
        }
    }

    private fun tryStart() {
        if (!validateAndSave()) return

        if (!StorageAccess.hasAccess(this)) {
            updateStorageUi()
            requestStorageAccess()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startServerNow()
    }

    private fun startServerNow() {
        FtpServerState.set(ServerState.Starting)
        val intent = Intent(this, FtpService::class.java).setAction(FtpService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun validateAndSave(): Boolean {
        var ok = true

        val port = binding.portInput.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            binding.portLayout.error = getString(R.string.err_port); ok = false
        } else binding.portLayout.error = null

        val user = binding.userInput.text.toString().trim()
        if (user.isEmpty() || user.any { it.isWhitespace() }) {
            binding.userLayout.error = getString(R.string.err_user); ok = false
        } else binding.userLayout.error = null

        val pass = binding.passwordInput.text.toString()
        if (pass.length < 4 || pass.any { it.isWhitespace() }) {
            binding.passwordLayout.error = getString(R.string.err_password); ok = false
        } else binding.passwordLayout.error = null

        val folder = binding.folderInput.text.toString().trim()
        if (!File(folder).isDirectory) {
            binding.folderLayout.error = getString(R.string.err_folder); ok = false
        } else binding.folderLayout.error = null

        if (ok) {
            settings.port = port!!
            settings.username = user
            settings.password = pass
            settings.homePath = folder
            settings.readOnly = binding.readOnlySwitch.isChecked
        }
        return ok
    }

    // --------------------------------------------------------- permissions

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                allFilesLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
