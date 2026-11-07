package com.malladi2610.phoneagent.wakeword

/** Keeps the last [capacity] audio samples. Not thread-safe; owned by the listening thread. */
class RingBuffer(val capacity: Int) {
  init {
    require(capacity > 0) { "capacity must be positive" }
  }

  private val data = FloatArray(capacity)
  private var next = 0 // index of the next write
  var size = 0
    private set

  fun write(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
    require(offset >= 0 && length >= 0 && offset + length <= samples.size) { "bad range" }
    var from = offset
    var count = length
    if (count > capacity) {
      from += count - capacity
      count = capacity
    }
    val first = minOf(count, capacity - next)
    System.arraycopy(samples, from, data, next, first)
    System.arraycopy(samples, from + first, data, 0, count - first)
    next = (next + count) % capacity
    size = minOf(capacity, size + count)
  }

  /** A copy of the buffered samples, oldest first. */
  fun snapshot(): FloatArray {
    val out = FloatArray(size)
    val start = (next - size + capacity) % capacity
    val first = minOf(size, capacity - start)
    System.arraycopy(data, start, out, 0, first)
    System.arraycopy(data, 0, out, first, size - first)
    return out
  }

  fun clear() {
    next = 0
    size = 0
  }
}
