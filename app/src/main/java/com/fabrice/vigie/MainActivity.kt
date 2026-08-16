package com.fabrice.vigie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.vigie.camera.VigieService
import com.fabrice.vigie.ui.MainScreen
import com.fabrice.vigie.ui.PinMode
import com.fabrice.vigie.ui.PinScreen
import com.fabrice.vigie.ui.theme.VigieTheme
import java.util.concurrent.Executors

class MainActivity : FragmentActivity() {

    private val vm: SurveillanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Le vieux téléphone reste branché : ne jamais laisser l'écran se verrouiller
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            VigieTheme {
                val locked by vm.locked.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    if (result[Manifest.permission.CAMERA] == true) {
                        VigieService.start(context)
                    }
                }

                // Demande des permissions puis démarrage du service foreground (caméra + logique)
                LaunchedEffect(Unit) {
                    val needed = buildList {
                        add(Manifest.permission.CAMERA)
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (needed.isNotEmpty()) {
                        permissionLauncher.launch(needed.toTypedArray())
                    } else {
                        VigieService.start(context)
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) vm.lock()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (locked) {
                    if (vm.isPinSet()) {
                        PinScreen(
                            mode = PinMode.VERIFY,
                            onVerify = { pin -> vm.verifyPin(pin) },
                            onBiometric = { showBiometricPrompt() },
                        )
                    } else {
                        PinScreen(
                            mode = PinMode.CREATE,
                            onPinValidated = { pin -> vm.setPin(pin) },
                        )
                    }
                } else {
                    MainScreen(vm)
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val bm = BiometricManager.from(this)
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return
        }
        val executor = Executors.newSingleThreadExecutor()
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    runOnUiThread { vm.unlock() }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Déverrouiller Vigie")
                .setSubtitle("Utilise ton empreinte ou ton visage")
                .setNegativeButtonText("Annuler")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        )
    }
}
