package com.malladi2610.phoneagent.core.ollama

import java.io.File

/** Reads the Ollama wire fixtures in `docs/superpowers/specs/fixtures/` (spec M1 §6). */
object Fixtures {
  fun read(name: String): String {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val f = File(dir, "docs/superpowers/specs/fixtures/$name")
      if (f.isFile) return f.readText()
      dir = dir.parentFile
    }
    error("Fixture $name not found above ${System.getProperty("user.dir")}")
  }
}
