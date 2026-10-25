package app.umbra.core.orchestrator

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.extension.AnnotatedElementContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDirFactory

/** Numeric-only JUnit directory suffixes can accidentally be valid card numbers in real DLP tests. */
class UuidTempDirFactory : TempDirFactory {
    override fun createTempDirectory(element: AnnotatedElementContext, context: ExtensionContext): Path =
        Files.createDirectory(Path.of(System.getProperty("java.io.tmpdir"), "umbra-test-${UUID.randomUUID()}"))
}
