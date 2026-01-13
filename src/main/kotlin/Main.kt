package dev.luna5ama.nvasmdump

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL46C.*
import org.lwjgl.opengl.NVMeshShader.GL_MESH_SHADER_NV
import org.lwjgl.opengl.NVMeshShader.GL_TASK_SHADER_NV
import org.lwjgl.system.MemoryStack
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ForkJoinPool
import java.util.stream.Collectors
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.system.exitProcess

enum class ShaderStage(
    val value: Int,
    val suffixes: Set<String>,
) {
    VertexShader(GL_VERTEX_SHADER, setOf("vert", "vsh")),
    TessCtrlShader(GL_TESS_CONTROL_SHADER, setOf("tesc", "tcs")),
    TessEvalShader(GL_TESS_EVALUATION_SHADER, setOf("tese", "tes")),
    GeometryShader(GL_GEOMETRY_SHADER, setOf("geom", "gsh")),
    FragmentShader(GL_FRAGMENT_SHADER, setOf("frag", "fsh")),
    ComputeShader(GL_COMPUTE_SHADER, setOf("comp", "csh")),
    TaskShader(GL_TASK_SHADER_NV, setOf("task")),
    MeshShader(GL_MESH_SHADER_NV, setOf("mesh"));

    companion object {
        private val suffixToStageMap: Map<String, ShaderStage> = entries.flatMap { stage ->
            stage.suffixes.map { suffix -> suffix to stage }
        }.toMap()

        fun fromSuffix(suffix: String): ShaderStage? = suffixToStageMap[suffix]
    }
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("Usage: <output directory> <input files...>")
            exitProcess(1)
        }

        val outputDir = Path(args[0])
        if (!outputDir.exists()) {
            outputDir.createDirectories()
        }
        fun dumpShaderAsm(programID: Int, name: String) {
            val binLen = glGetProgrami(programID, GL_PROGRAM_BINARY_LENGTH)
            val outputPath = outputDir.resolve("$name.txt")
            FileChannel.open(
                outputPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { channel ->
                val mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, binLen.toLong())
                    .order(ByteOrder.nativeOrder())
                MemoryStack.stackPush().use {
                    val binaryFormat = it.callocInt(1)
                    glGetProgramBinary(programID, null, binaryFormat, mapped)
                }
            }
        }

        val validSuffixes = setOf(
            "glsl",
            "vert", "frag", "comp", "geom", "tesc", "tese", "mesh", "task",
            "vsh", "fsh", "csh", "gsh", "tcs", "tes"
        )

        data class Shader(val stage: ShaderStage, val path: Path)
        data class Program(val name: String, val compute: Shader?, val raster: List<Shader>?)

        val inputShaders = args.asSequence()
            .drop(1)
            .flatMap {
                val globIndex = it.indexOf('*')

                if (globIndex == -1) {
                    sequenceOf(Path(it))
                } else {
                    val fixedPathStr = it.replace(File.separator, "/")

                    val baseDirIndex = fixedPathStr.subSequence(0..<globIndex).lastIndexOf("/")
                    val baseDir = if (baseDirIndex == -1) {
                        Path(".")
                    } else {
                        Path(fixedPathStr.substring(0..baseDirIndex))
                    }
                    val globPattern = fixedPathStr.substring(baseDirIndex + 1)
                    baseDir.listDirectoryEntries(globPattern).asSequence()
                }
            }
            .filter { it.extension in validSuffixes }
            .toList()

        fun Path.shaderExtension(): String {
            return nameWithoutExtension.substringBeforeLast('.').takeIf { it in validSuffixes } ?: extension
        }

        val inputPrograms = inputShaders.groupBy { it.nameWithoutExtension.substringBeforeLast(".") }
            .mapTo(ConcurrentLinkedQueue()) { (name, paths) ->
                val computeShader = paths.firstNotNullOfOrNull {
                    val ext = it.shaderExtension()
                    val stage = ShaderStage.fromSuffix(ext) ?: return@firstNotNullOfOrNull null
                    if (stage == ShaderStage.ComputeShader) {
                        Shader(ShaderStage.ComputeShader, it)
                    } else {
                        null
                    }
                }
                val rasterShaders = paths.mapNotNull {
                    val ext = it.shaderExtension()
                    val stage = ShaderStage.fromSuffix(ext) ?: return@mapNotNull null
                    if (stage != ShaderStage.ComputeShader) {
                        Shader(stage, it)
                    } else {
                        null
                    }
                }.takeIf { it.isNotEmpty() }
                Program(name, computeShader, rasterShaders)
            }

        val allSourcesPending = ForkJoinPool.commonPool().submit(Callable {
            val allRequiredShaders = inputPrograms.flatMap {
                sequence {
                    if (it.compute != null) {
                        yield(it.compute)
                    }
                    if (it.raster != null) {
                        for (shader in it.raster) {
                            yield(shader)
                        }
                    }
                }
            }

            allRequiredShaders.parallelStream()
                .map { it to it.path.readText() }
                .collect(Collectors.toList())
                .toMap()
        })

        try {
            glfwInit()
            GLFWErrorCallback.createPrint(System.err).set()

            val threadCount = minOf(Runtime.getRuntime().availableProcessors(), (inputPrograms.size + 15) / 16)

            val windows = List(threadCount) {
                glfwDefaultWindowHints()
                glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
                glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
                glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, 1)
                glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE)
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
                glfwWindowHint(GLFW_SAMPLES, 0)

                glfwCreateWindow(640, 480, "", 0, 0)
            }

            windows.map { window ->
                window to thread(start = true) {
                    glfwMakeContextCurrent(window)
                    runCatching { GL.create() }
                    GL.createCapabilities()

                    val allSources = allSourcesPending.get()
                    fun createProgram(shaders: List<Shader>): Int {
                        val program = glCreateProgram()
                        val shaderIDs = shaders.map {
                            val shaderID = glCreateShader(it.stage.value)
                            val source = allSources[it] ?: error("Missing source for shader: ${it.path}")
                            glShaderSource(shaderID, source)
                            glCompileShader(shaderID)
                            glAttachShader(program, shaderID)
                            shaderID
                        }
                        glLinkProgram(program)
                        shaderIDs.forEach {
                            glDetachShader(program, it)
                            glDeleteShader(it)
                        }
                        return program
                    }

                    var program = inputPrograms.poll()
                    while (program != null) {
                        if (program.compute != null) {
                            val programID = createProgram(listOf(program.compute))
                            dumpShaderAsm(programID, program.name + "_comp")
                            glDeleteProgram(programID)
                        }

                        if (program.raster != null) {
                            val programID = createProgram(program.raster)
                            dumpShaderAsm(programID, program.name + "_raster")
                            glDeleteProgram(programID)
                        }
                        program = inputPrograms.poll()
                    }
                }
            }.forEach {
                it.second.join()
                glfwFreeCallbacks(it.first)
                glfwDestroyWindow(it.first)
            }
        } finally {
            GL.destroy()
            glfwTerminate()
        }
    }
}