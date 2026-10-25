package app.umbra.runtime.linux

import java.util.Base64

/**
 * Credentials reach a guest command through its stdin, never through a file: a file in the guest, however private
 * and short-lived, can be read by another process of the same user before it is deleted (security review item E).
 * The wrapper reads `NAME=base64` lines up to [END], exports them and then `exec`s the command. bash reads a pipe one
 * byte at a time, so everything after [END] (omp's RPC stream) is left for the command.
 */
internal object SecretStdin {
    const val END = "__UMBRA_SECRETS_END__"

    /** Shell that consumes the secret lines; part of the wrapper, so it contains no values. */
    const val PREAMBLE = "while IFS= read -r __umbra_l; do [ \"\$__umbra_l\" = \"$END\" ] && break; " +
        "__umbra_k=\${__umbra_l%%=*}; export \"\$__umbra_k=\$(printf '%s' \"\${__umbra_l#*=}\" | base64 -d)\"; done; unset __umbra_l __umbra_k; "

    /** The lines to write first on the command's stdin. Values travel base64-encoded, so any byte is allowed. */
    fun lines(secrets: Map<String, String>): List<String> = secrets.map { (k, v) ->
        require(k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !k.startsWith("__umbra_")) { "bad environment variable name '$k'" }
        "$k=" + Base64.getEncoder().encodeToString(v.toByteArray())
    } + END

    fun payload(secrets: Map<String, String>): String = lines(secrets).joinToString("\n", postfix = "\n")
}
