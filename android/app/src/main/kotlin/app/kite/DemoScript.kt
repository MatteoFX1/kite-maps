package app.kite

/** Screenshot runs only: puts the app in a known state so the build can check the look. */
object DemoScript {
    var step: String? = null; private set
    val active get() = step != null
    fun start(model: AppModel, s: String) { step = s }
}
