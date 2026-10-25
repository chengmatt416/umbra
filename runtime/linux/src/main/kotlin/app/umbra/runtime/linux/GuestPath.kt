package app.umbra.runtime.linux

internal fun guestPath(path: String): String {
    require(path.startsWith('/') && '\u0000' !in path) { "Expected an absolute guest path" }
    require(path.split('/').none { it == "." || it == ".." }) { "Guest paths cannot contain . or .." }
    return "/" + path.split('/').filter(String::isNotEmpty).joinToString("/")
}

internal fun fileMode(mode: String): String {
    require(mode.matches(Regex("[0-7]{3}"))) { "Expected an ordinary three-digit file mode" }
    return mode
}
