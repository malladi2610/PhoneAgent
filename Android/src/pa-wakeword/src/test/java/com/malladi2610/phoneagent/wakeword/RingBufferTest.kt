package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RingBufferTest {
  private fun floats(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }

  @Test
  fun partialFill_returnsWhatWasWritten() {
    val ring = RingBuffer(5)
    ring.write(floats(1, 2))
    assertEquals(2, ring.size)
    assertArrayEquals(floats(1, 2), ring.snapshot(), 0f)
  }

  @Test
  fun keepsLastNSamples_oldestToNewest() {
    val ring = RingBuffer(5)
    ring.write(floats(1, 2, 3))
    ring.write(floats(4, 5, 6))
    ring.write(floats(7))
    assertEquals(5, ring.size)
    assertArrayEquals(floats(3, 4, 5, 6, 7), ring.snapshot(), 0f)
  }

  @Test
  fun writeLargerThanCapacity_keepsTail() {
    val ring = RingBuffer(3)
    ring.write(floats(1, 2, 3, 4, 5, 6, 7))
    assertArrayEquals(floats(5, 6, 7), ring.snapshot(), 0f)
    ring.write(floats(8))
    assertArrayEquals(floats(6, 7, 8), ring.snapshot(), 0f)
  }

  @Test
  fun writeWithOffsetAndLength() {
    val ring = RingBuffer(4)
    ring.write(floats(9, 1, 2, 9), offset = 1, length = 2)
    assertArrayEquals(floats(1, 2), ring.snapshot(), 0f)
  }

  @Test
  fun clear_empties() {
    val ring = RingBuffer(3)
    ring.write(floats(1, 2, 3, 4))
    ring.clear()
    assertEquals(0, ring.size)
    assertEquals(0, ring.snapshot().size)
    ring.write(floats(5))
    assertArrayEquals(floats(5), ring.snapshot(), 0f)
  }

  @Test
  fun snapshot_isACopy() {
    val ring = RingBuffer(2)
    ring.write(floats(1, 2))
    val snap = ring.snapshot()
    ring.write(floats(3))
    assertArrayEquals(floats(1, 2), snap, 0f)
  }
}
