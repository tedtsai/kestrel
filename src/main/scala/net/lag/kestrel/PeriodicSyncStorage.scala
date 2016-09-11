package net.lag.kestrel

import com.twitter.conversions.time._
import com.twitter.ostrich.stats.Stats
import com.twitter.logging.Logger
import com.twitter.util._
import java.io.{IOException, FileOutputStream, File}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.{ConcurrentLinkedQueue, ScheduledExecutorService, ScheduledFuture, TimeUnit}

abstract class PeriodicSyncTask(val scheduler: ScheduledExecutorService, initialDelay: Duration, period: Duration)
extends Runnable {
  @volatile private[this] var scheduledFsync: Option[ScheduledFuture[_]] = None

  def start() {
    synchronized {
      if (scheduledFsync.isEmpty && period > 0.seconds) {
        val handle = scheduler.scheduleWithFixedDelay(this, initialDelay.inMilliseconds, period.inMilliseconds,
                                                      TimeUnit.MILLISECONDS)
        scheduledFsync = Some(handle)
      }
    }
  }

  def stop() {
    synchronized { _stop() }
  }

  def stopIf(f: => Boolean) {
    synchronized {
      if (f) _stop()
    }
  }

  private[this] def _stop() {
    scheduledFsync.foreach { _.cancel(false) }
    scheduledFsync = None
  }
}

/**
 * Open a file for writing, and fsync it on a schedule. The period may be 0 to force an fsync
 * after every write, or `Duration.MaxValue` to never fsync.
 */
class PeriodicSyncStorage(writer: FileChannel, scheduler: ScheduledExecutorService, period: Duration) {
  // pre-completed future for writers who are behaving synchronously.
  private final val DONE = Future.Unit

  private val log = Logger.get(getClass)

  case class TimestampedPromise(val promise: Promise[Unit], val time: Time)

  val promises = new ConcurrentLinkedQueue[TimestampedPromise]()
  val periodicSyncTask = new PeriodicSyncTask(scheduler, period, period) {
    override def run() {
      if (!closed && !promises.isEmpty) fsync()
    }
  }

  @volatile var closed = false

  def handleFsyncError(t: Throwable, completed: Int) {
    Stats.incr("fsync_error_" + t.getClass.getName)
    if (t.isInstanceOf[IOException]) {
      for (i <- 0 until completed) {
        promises.poll().promise.setException(t)
      }  
    }
  }

  def fsync() {
    synchronized {
      // race: we could underestimate the number of completed writes. that's okay.
      val completed = promises.size
      val fsyncStart = Time.now
      try {
        writer.force(false)
      } catch {
        case NonFatal(t) => 
          handleFsyncError(t, completed)
          return
      }

      for (i <- 0 until completed) {
        val timestampedPromise = promises.poll()
        timestampedPromise.promise.setValue(())
        val delaySinceWrite = fsyncStart - timestampedPromise.time
        val durationBehind = if (delaySinceWrite > period) delaySinceWrite - period else 0.seconds
        Stats.addMetric("item_fsync_delay_usec", durationBehind.inMicroseconds.toInt)
      }

      val fsyncUs = (Time.now - fsyncStart).inMicroseconds.toInt
      Stats.addMetric("fsync_delay_usec", if (fsyncUs > 0) fsyncUs else 0)

      periodicSyncTask.stopIf { promises.isEmpty }
    }
  }

  def write(buffer: ByteBuffer): Future[Unit] = {
    do {
      writer.write(buffer)
    } while (buffer.position < buffer.limit)
    if (period == 0.seconds) {           // flush间隔 0，写完后立即flush同步到磁盘
      try {
        writer.force(false)
        DONE
      } catch {
        case e: IOException =>
          Future.exception(e)
      }
    } else if (period == Duration.Top) {      // 最大间隔，永远不会flush
      // not fsync'ing.
      DONE
    } else {                                 //其他情况，开始后台flush线程
      val promise = new Promise[Unit]()
      promises.add(TimestampedPromise(promise, Time.now))
      periodicSyncTask.start()
      promise
    }
  }

  /**
   * No locking is done here. Be sure you aren't doing concurrent writes or they may be lost
   * forever, and nobody will cry.
   */
  def close() {
    // not safe to call fsync if we're already closed.
    if (!closed) {
      closed = true
      periodicSyncTask.stop()
      fsync()
      writer.close()
    }
  }

  def position: Long = writer.position
}
