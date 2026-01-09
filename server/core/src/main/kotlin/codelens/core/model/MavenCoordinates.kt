package codelens.core.model

import kotlinx.serialization.Serializable

/**
 * Maven artifact coordinates (groupId:artifactId:version).
 */
@Serializable
data class MavenCoordinates(
    val groupId: String,
    val artifactId: String,
    val version: String
) {
    /**
     * Returns Gradle-style notation: "groupId:artifactId:version"
     */
    fun toGradleNotation(): String = "$groupId:$artifactId:$version"

    /**
     * Returns Maven repository path: "groupId/path/artifactId/version"
     */
    fun toRepositoryPath(): String = "${groupId.replace('.', '/')}/$artifactId/$version"

    /**
     * Returns the expected source JAR filename.
     */
    fun sourceJarName(): String = "$artifactId-$version-sources.jar"

    /**
     * Returns the main JAR filename.
     */
    fun jarName(): String = "$artifactId-$version.jar"

    /**
     * Returns the full Maven Central URL for the source JAR.
     */
    fun sourceJarUrl(): String =
        "https://repo1.maven.org/maven2/${toRepositoryPath()}/${sourceJarName()}"

    companion object {
        /**
         * Parse from "groupId:artifactId:version" format.
         */
        fun parse(notation: String): MavenCoordinates? {
            val parts = notation.split(":")
            return if (parts.size >= 3) {
                MavenCoordinates(parts[0], parts[1], parts[2])
            } else null
        }

        /**
         * Parses coordinates from a mapping line: "groupId:artifactId:version|jarPath"
         * Returns null if the format is invalid.
         */
        fun parseFromMapping(line: String): Pair<MavenCoordinates, String>? {
            val pipeIndex = line.indexOf('|')
            if (pipeIndex == -1) return null

            val notation = line.substring(0, pipeIndex)
            val jarPath = line.substring(pipeIndex + 1)

            val coords = parse(notation) ?: return null
            return coords to jarPath
        }
    }
}
