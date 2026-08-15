package com.fabrice.vigie

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.vigie.ui.MainScreen
import com.fabrice.vigie.ui.PinMode
import com.fabrice.vigie.ui.PinScreen
import com.fabrice.vigie.ui.theme.VigieTheme

class MainActivity : ComponentActivity() {

    private val vm: SurveillanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Le vieux téléphone reste branché : ne jamais laisser l'écran se verrouiller
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            VigieTheme {
                val locked by vm.locked.collectAsStateWithLifecycle()

                val lifecycleOwner = LocalLifecycleOwner.current
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
}
