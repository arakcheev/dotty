package dotty.runtime

import scala.annotation.tailrec

/**
 * Helper methods used in thread-safe lazy vals.
 */
object LazyVals {
  private val unsafe = scala.concurrent.util.Unsafe.instance

  final val BITS_PER_LAZY_VAL = 2
  final val LAZY_VAL_MASK = 3

  @inline def STATE(cur: Long, ord: Long) = (cur >> (ord * BITS_PER_LAZY_VAL)) & LAZY_VAL_MASK
  @inline def CAS(t: Object, offset: Long, e: Long, v: Long, ord: Int) = {
    val mask = ~(LAZY_VAL_MASK << ord * BITS_PER_LAZY_VAL)
    val n = (e & mask) | (v << (ord * BITS_PER_LAZY_VAL))
    compareAndSet(t, offset, e, n)
  }
  @inline def setFlag(t: Object, offset: Long, v: Int, ord: Int) = {
    var retry = true
    while (retry) {
      val cur = get(t, offset)
      if (STATE(cur, ord) == 1) retry = CAS(t, offset, cur, v, ord)
      else {
        // cur == 2, somebody is waiting on monitor
        if (CAS(t, offset, cur, v, ord)) {
          val monitor = getMonitor(t, ord)
          monitor.synchronized {
            monitor.notifyAll()
          }
          retry = false
        }
      }
    }
  }
  @inline def wait4Notification(t: Object, offset: Long, cur: Long, ord: Int) = {
    var retry = true
    while (retry) {
      val cur = get(t, offset)
      val state = STATE(cur, ord)
      if (state == 1) CAS(t, offset, cur, 2, ord)
      else if (state == 2) {
        val monitor = getMonitor(t, ord)
        monitor.synchronized {
          monitor.wait()
        }
      }
      else retry = false
    }
  }

  @inline def compareAndSet(t: Object, off: Long, e: Long, v: Long) = unsafe.compareAndSwapLong(t, off, e, v)
  @inline def get(t: Object, off: Long) = unsafe.getLongVolatile(t, off)

  val processors: Int = java.lang.Runtime.getRuntime.availableProcessors()
  val base: Int = 8 * processors * processors
  val monitors: Array[Object] = (0 to base).map {
    x => new Object()
  }.toArray

  @inline def getMonitor(obj: Object, fieldId: Int = 0) = {
    var id = (java.lang.System.identityHashCode(obj) + fieldId) % base
    if (id < 0) id += base
    monitors(id)
  }

  @inline def getOffset(obj: Object, name: String) = unsafe.objectFieldOffset(obj.getClass.getDeclaredField(name))

  object Names {
    final val state = "STATE"
    final val cas = "CAS"
    final val setFlag = "setFlag"
    final val wait4Notification = "wait4Notification"
    final val compareAndSet = "compareAndSet"
    final val get = "get"
    final val getOffset = "getOffset"
  }
}
