package com.arv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arv.app.core.sync.SyncScheduler
import com.arv.app.ui.ArvAppRoot
import com.arv.app.ui.theme.ArvTheme

/**
 * The only activity. Compose draws everything from [ArvAppRoot], so there are no fragments and
 * no second entry point that has to be kept in step with this one.
 *
 * Sync is asked to run from [onStart] and not from [onCreate] because returning to the app is
 * the moment a person expects to see what the family added, and only [onStart] runs then. A
 * rotation runs both again. What keeps turning the phone from starting a sync every time is
 * the minute [SyncScheduler.onAppVisible] waits between visible pulls.
 */
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
