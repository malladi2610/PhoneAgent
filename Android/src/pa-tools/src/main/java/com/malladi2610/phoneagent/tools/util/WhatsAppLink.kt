package com.malladi2610.phoneagent.tools.util

import java.net.URLEncoder

/** Builds WhatsApp click-to-chat links: `https://wa.me/<digits>?text=<percent-encoded>`. */
object WhatsAppLink {
  fun build(number: String, text: String): String {
    val digits = number.filter { it.isDigit() }
    if (text.isEmpty()) return "https://wa.me/$digits"
    // URLEncoder does form encoding (space → '+'); wa.me wants %20.
    val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    return "https://wa.me/$digits?text=$encoded"
  }
}
