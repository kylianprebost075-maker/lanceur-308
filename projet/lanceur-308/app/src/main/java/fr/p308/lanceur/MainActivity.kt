package fr.p308.lanceur

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import fr.p308.lanceur.service.VehicleWatchService
import fr.p308.lanceur.ui.LanceurApp
import fr.p308.lanceur.ui.LanceurTheme

class MainActivity : ComponentActivity() {
    // Le service relit les permissions à chaque démarrage : on le relance après la réponse.
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        VehicleWatchService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LanceurTheme { LanceurApp() } }

        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        VehicleWatchService.start(this)
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.foreground.value = true
    }

    override fun onPause() {
        AppVisibility.foreground.value = false
        super.onPause()
    }
}
