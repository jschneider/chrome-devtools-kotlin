package org.hildan.chrome.devtools.build

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.hildan.chrome.devtools.build.generator.Generator
import java.nio.file.Path
import java.nio.file.Paths

open class GenerateProtocolApiTask : DefaultTask() {

    init {
        group = "protocol"
    }

    @InputFiles
    val protocolPaths = project.files("protocol/browser_protocol.json", "protocol/js_protocol.json")

    @InputFile
    val targetTypesPath = project.file("protocol/target_types.json")

    @OutputDirectory
    val outputDirPath = project.file("src/main/generated")

    @TaskAction
    fun generate() {
        println("Generating Chrome DevTools Protocol API...")
        Generator(protocolPaths.map { it.toPath() }, targetTypesPath.toPath(), outputDirPath.toPath()).generate()
        println("Done.")
    }
}
