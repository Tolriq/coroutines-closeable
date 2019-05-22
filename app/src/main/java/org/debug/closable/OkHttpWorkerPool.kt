package org.debug.closable

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// Based on suggestion from Zach (https://gist.github.com/zach-klippenstein/38bd9a6fc4a84ee3e2435956ada18504)
class OkHttpWorkerPool(
    okHttpClient: OkHttpClient,
    private val maxConcurrentTasks: Int
) : CoroutineScope {

    internal class Task(val request: Request, val timeout: Long) {
        val response = CompletableDeferred<WorkerResult>()
    }

    internal val tasks = Channel<Task>(UNLIMITED)

    private val httpClient = okHttpClient.newBuilder().addInterceptor(OkHttpExceptionInterceptor()).build()

    override val coroutineContext = Dispatchers.IO + SupervisorJob()

    fun startWorkers() {
        repeat(maxConcurrentTasks) {
            launch {
                for (task in tasks) executeTask(task)
            }
        }
    }

    suspend fun execute(request: Request, timeout: Long = -1L): WorkerResult {
        val task = Task(request, timeout)
        tasks.send(task)
        return try {
            task.response.await()
        } catch (e: CancellationException) {
            if (task.response.isCompleted) {
                try {
                    (task.response.getCompleted() as? WorkerResult.Success)?.response?.close()
                } catch (ignore: Throwable) {
                    // Ignore
                }
            }
            throw e
        }
    }

    /** @param immediately If false, will finish processing all queued tasks before terminating workers. */
    fun close(immediately: Boolean = false) {
        tasks.close()
        if (immediately) {
            coroutineContext.cancel()
        }
    }

    private suspend fun executeTask(task: Task) {
        try {
            val response = httpClient
                .apply {
                    if (task.timeout > 0) {
                        newBuilder().readTimeout(task.timeout, TimeUnit.MILLISECONDS).build()
                    }
                }
                .newCall(task.request).await()
            task.response.complete(response)
        } catch (e: Throwable) {
            task.response.completeExceptionally(e)
        }
    }

    private suspend fun Call.await(): WorkerResult {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancel()
            }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(WorkerResult.Success(response)) {
                        try {
                            response.close()
                        } catch (ignore: Throwable) {
                            // Ignore
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled || !isActive) {
                        continuation.resume(WorkerResult.Error(CancellationException("Cancelled")))
                    } else {
                        continuation.resume(WorkerResult.Error(e))
                    }
                }
            })
        }
    }

    /* See https://github.com/square/okhttp/issues/3477 */
    private class OkHttpExceptionInterceptor : Interceptor {

        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            try {
                return chain.proceed(chain.request())
            } catch (e: Throwable) {
                if (e is IOException) {
                    throw e
                } else {
                    throw IOException(e)
                }
            }
        }
    }

    sealed class WorkerResult {
        class Success(val response: Response) : WorkerResult()
        class Error(val exception: Throwable) : WorkerResult()
    }
}
