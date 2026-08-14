package com.example
 
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.ui.screens.MainAppContainer
import com.example.ui.screens.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
 
class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private lateinit var vpnPrepareLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fully support full-screen edge-to-edge transparent drawing and custom system colors
        enableEdgeToEdge()
        
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Register the activity result launcher after the ViewModel is available to avoid
        // referencing an uninitialized lateinit property from the callback.
        vpnPrepareLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Use safe call in case ViewModel is unexpectedly not initialized
                try {
                    mainViewModel.toggleVpnAfterPermission()
                } catch (e: UninitializedPropertyAccessException) {
                    Toast.makeText(this, "خطا در دسترسی به ViewModel پس از دریافت مجوز VPN.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "امکان برقراری اتصال بدون تایید مجوز VPN وجود ندارد.", Toast.LENGTH_SHORT).show()
            }
        }
        
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.example.ui.theme.SlateDark
                ) {
                    MainAppContainer(viewModel = mainViewModel)
                }
            }
        }

        // Observe VPN permission requests from ViewModel
        lifecycleScope.launch {
            mainViewModel.vpnPermissionRequest.collect { intent ->
                try {
                    vpnPrepareLauncher.launch(intent)
                } catch (e: Exception) {
                    // Fallback: if launcher cannot be used for any reason, attempt to continue logic
                    mainViewModel.toggleVpnAfterPermission()
                }
            }
        }
    }
}
