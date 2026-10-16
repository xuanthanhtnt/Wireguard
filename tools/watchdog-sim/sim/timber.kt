package timber.log
object Timber {
    var verbose = false
    fun d(msg: String, vararg a: Any?) { if (verbose) println("  [d] $msg") }
    fun i(msg: String, vararg a: Any?) { println("  [i] $msg") }
    fun w(t: Throwable?, msg: String, vararg a: Any?) { println("  [w] $msg ${t?.message}") }
    fun w(msg: String, vararg a: Any?) { println("  [w] $msg") }
    fun e(t: Throwable?, msg: String, vararg a: Any?) { println("  [E] $msg ${t}") ; t?.printStackTrace() }
    fun e(t: Throwable?) { println("  [E] $t"); t?.printStackTrace() }
}
