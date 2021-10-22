package org.signal.core.util.concurrent

import android.os.Handler
import org.signal.core.util.logging.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor

/**
 * A class that polls active threads at a set interval and logs when multiple threads are BLOCKED.
 */
class DeadlockDetector(private val handler: Handler, private val pollingInterval: Long) {

  private var running = false
  private val previouslyBlocked: MutableSet<Long> = mutableSetOf()

  fun start() {
    Log.d(TAG, "Beginning deadlock monitoring.");
    running = true
    handler.postDelayed(this::poll, pollingInterval)
  }

  fun stop() {
    Log.d(TAG, "Ending deadlock monitoring.");
    running = false
    handler.removeCallbacksAndMessages(null)
  }

  private fun poll() {
    val threads: Map<Thread, Array<StackTraceElement>> = Thread.getAllStackTraces()
    val blocked: Map<Thread, Array<StackTraceElement>> = threads.filter { entry ->
      val thread: Thread = entry.key
      val stack: Array<StackTraceElement> = entry.value

      thread.state == Thread.State.BLOCKED || (thread.state == Thread.State.WAITING && stack.any { it.methodName.startsWith("lock") })
    }
    val blockedIds: Set<Long> = blocked.keys.map(Thread::getId).toSet()
    val stillBlocked: Set<Long> = blockedIds.intersect(previouslyBlocked)

    if (blocked.size > 1) {
      Log.w(TAG, buildLogString("Found multiple blocked threads! Possible deadlock.", blocked))
    } else if (stillBlocked.isNotEmpty()) {
      val stillBlockedMap: Map<Thread, Array<StackTraceElement>> = stillBlocked
        .map { blockedId ->
          val key: Thread = blocked.keys.first { it.id == blockedId }
          val value: Array<StackTraceElement> = blocked[key]!!
          Pair(key, value)
        }
        .toMap()

      Log.w(TAG, buildLogString("Found a long block! Blocked for at least $pollingInterval ms.", stillBlockedMap))
    }

    val fullExecutors: List<ExecutorInfo> = EXECUTORS.filter { isExecutorFull(it.executor) }

    if (fullExecutors.isNotEmpty()) {
      fullExecutors.forEach { executorInfo ->
        val fullMap: Map<Thread, Array<StackTraceElement>> = threads
          .filter { it.key.name.startsWith(executorInfo.namePrefix) }
          .toMap()

        val executor: ThreadPoolExecutor = executorInfo.executor as ThreadPoolExecutor
        Log.w(TAG, buildLogString("Found a full executor! ${executor.activeCount}/${executor.corePoolSize} threads active with ${executor.queue.size} tasks queued.", fullMap))
      }
    }

    previouslyBlocked.clear()
    previouslyBlocked.addAll(blockedIds)

    if (running) {
      handler.postDelayed(this::poll, pollingInterval)
    }
  }

  private data class ExecutorInfo(
    val executor: ExecutorService,
    val namePrefix: String
  )

  companion object {
    private val TAG = Log.tag(DeadlockDetector::class.java)

    private val EXECUTORS: Set<ExecutorInfo> = setOf(
      ExecutorInfo(SignalExecutors.BOUNDED, "signal-bounded-"),
      ExecutorInfo(SignalExecutors.BOUNDED_IO, "signal-io-bounded")
    )

    private const val CONCERNING_QUEUE_THRESHOLD = 4

    private fun buildLogString(description: String, blocked: Map<Thread, Array<StackTraceElement>>): String {
      val stringBuilder = StringBuilder()
      stringBuilder.append(description).append("\n")

      for (entry in blocked) {
        stringBuilder.append("-- [${entry.key.id}] ${entry.key.name} | ${entry.key.state}\n")

        for (element in entry.value) {
          stringBuilder.append("$element\n")
        }

        stringBuilder.append("\n")
      }

      return stringBuilder.toString()
    }

    private fun isExecutorFull(executor: ExecutorService): Boolean {
      return if (executor is ThreadPoolExecutor) {
        executor.queue.size > CONCERNING_QUEUE_THRESHOLD
      } else {
        false
      }
    }
  }
}