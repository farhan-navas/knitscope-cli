package app

import com.github.ajalt.clikt.core.CliktCommand

class Root : CliktCommand(
    name = "knitscope",
) {
    override fun run() = Unit
}
