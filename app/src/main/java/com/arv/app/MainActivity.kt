package com.arv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arv.app.core.sync.SyncScheduler
import com.arv.app.ui.ArvAppRoot
import com.arv.app.ui.theme.ArvTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ArvTheme {
                ArvAppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is when a person expects to see what the family added. Does nothing
        // for an archive that is not shared.
        SyncScheduler.onAppVisible(this)
    }
}
