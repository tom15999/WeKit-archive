package dev.ujhhgtg.wekit.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

class StandardActivity : ComponentActivity() {

//    private var onResumeAction: (ComponentActivity.() -> Unit)? = null
//    private var onPauseAction: (ComponentActivity.() -> Unit)? = null
//    private var onDestroyAction: (ComponentActivity.() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val action = pendingAction ?: run { finish(); return }
        pendingAction = null

//        onResumeAction = pendingOnResume
//        onPauseAction = pendingOnPause
//        onDestroyAction = pendingOnDestroy
//        pendingOnResume = null
//        pendingOnPause = null
//        pendingOnDestroy = null

        action(this)
    }

//    override fun onResume() {
//        super.onResume()
//        onResumeAction?.invoke(this)
//    }
//
//    override fun onPause() {
//        super.onPause()
//        onPauseAction?.invoke(this)
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        onDestroyAction?.invoke(this)
//    }

    companion object {
        @Volatile
        private var pendingAction: (ComponentActivity.() -> Unit)? = null

//        @Volatile
//        private var pendingOnResume: (ComponentActivity.() -> Unit)? = null
//
//        @Volatile
//        private var pendingOnPause: (ComponentActivity.() -> Unit)? = null
//
//        @Volatile
//        private var pendingOnDestroy: (ComponentActivity.() -> Unit)? = null

        fun launch(
            context: Context,
//            onResume: (ComponentActivity.() -> Unit)? = null,
//            onPause: (ComponentActivity.() -> Unit)? = null,
//            onDestroy: (ComponentActivity.() -> Unit)? = null,
            action: ComponentActivity.() -> Unit
        ) {
            pendingAction = action
//            pendingOnResume = onResume
//            pendingOnPause = onPause
//            pendingOnDestroy = onDestroy
            context.startActivity(
                Intent(context, StandardActivity::class.java)
            )
        }
    }
}
