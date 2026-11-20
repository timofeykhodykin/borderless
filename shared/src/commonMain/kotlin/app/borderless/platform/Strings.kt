package app.borderless.platform

/** A text of the UI language by its resource name ([app.borderless.S]), rendered by the platform ([Host.text]). */
fun text(name: String, vararg args: Any): String = Host.text(name, args.toList())
