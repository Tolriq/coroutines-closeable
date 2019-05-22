package org.debug.closable

import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext


class MainActivity : AppCompatActivity(), CoroutineScope {

    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    private val mockServer = MockWebServer()
    private var okHttpWorkerPool: OkHttpWorkerPool? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        launch {
            val dispatcher = QueueDispatcher()
            dispatcher.setFailFast(true)
            dispatcher.setFailFast(MockResponse().setBody("Toto").setHeadersDelay(10, TimeUnit.MILLISECONDS))
            mockServer.dispatcher = dispatcher
            mockServer.start()
        }
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .penaltyDeath()
                .build()
        )

        fab.setOnClickListener {
            okHttpWorkerPool?.close(true)
            okHttpWorkerPool = OkHttpWorkerPool(OkHttpClient(), 4).apply {
                startWorkers()
            }
            repeat(10_000) {
                val job = launch {
                    val id = (Math.random() * 1000).toInt()
                    when (val result = getResult()) {
                        is OkHttpWorkerPool.WorkerResult.Success -> result.response.body()?.close()
                        is OkHttpWorkerPool.WorkerResult.Error -> Log.e("Debug", "[$id] Result: ${result.exception}")
                        else -> Log.e("Debug", "[$id] Result is null")
                    }
                }
                job.cancel()
            }
            launch {
                delay(50)
                okHttpWorkerPool?.close(true)
                coroutineContext.cancelChildren()
                System.gc()
            }
        }
    }

    private suspend fun getResult(): OkHttpWorkerPool.WorkerResult? {
        return okHttpWorkerPool?.execute(Request.Builder().url(mockServer.url("/")).build())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }
}
