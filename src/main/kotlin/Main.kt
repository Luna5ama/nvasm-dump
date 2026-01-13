package dev.luna5ama.nvasmdump

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL46C.*
import org.lwjgl.opengl.KHRParallelShaderCompile
import org.lwjgl.opengl.NVMeshShader.GL_MESH_SHADER_NV
import org.lwjgl.opengl.NVMeshShader.GL_TASK_SHADER_NV
import org.lwjgl.system.MemoryStack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.stream.Collectors
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.system.exitProcess

@Serializable
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

@Serializable
data class CacheInfo(
    val outputHash: String,
    val inputHashes: Map<ShaderStage, String>
)

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("Usage: <output directory> <input shader files>...")
            exitProcess(1)
        }

        val outputDir = Path(args[0])
        if (!outputDir.exists()) {
            outputDir.createDirectories()
        }

        val validSuffixes = setOf(
            "glsl",
            "vert", "frag", "comp", "geom", "tesc", "tese", "mesh", "task",
            "vsh", "fsh", "csh", "gsh", "tcs", "tes"
        )

        data class PartialShader(val stage: ShaderStage, val path: Path)
        data class Shader(val stage: ShaderStage, val path: Path, val srcBytes: ByteBuffer, val hash: String)
        data class Program<T>(val name: String, val compute: T?, val raster: List<T>?)

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
            .map { (name, paths) ->
                val computeShader = paths.firstNotNullOfOrNull {
                    val ext = it.shaderExtension()
                    val stage = ShaderStage.fromSuffix(ext) ?: return@firstNotNullOfOrNull null
                    if (stage == ShaderStage.ComputeShader) {
                        PartialShader(ShaderStage.ComputeShader, it)
                    } else {
                        null
                    }
                }
                val rasterShaders = paths.mapNotNull {
                    val ext = it.shaderExtension()
                    val stage = ShaderStage.fromSuffix(ext) ?: return@mapNotNull null
                    if (stage != ShaderStage.ComputeShader) {
                        PartialShader(stage, it)
                    } else {
                        null
                    }
                }.takeIf { it.isNotEmpty() }
                Program(name, computeShader, rasterShaders)
            }

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

        fun hashBytes(mapped: ByteBuffer): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(mapped)
            return digest.digest().joinToString("") { byte -> "%02X".format(byte) }
        }

        val allSourcesPending = ForkJoinPool.commonPool().submit(Callable {
            allRequiredShaders.parallelStream()
                .map {
                    val sourceBytes = ByteBuffer.allocateDirect(it.path.fileSize().toInt())
                        .order(ByteOrder.nativeOrder())
                    FileChannel.open(it.path, StandardOpenOption.READ).use { channel ->
                        channel.read(sourceBytes)
                    }
                    sourceBytes.flip()
                    val hashHEX = hashBytes(sourceBytes.duplicate())
                    it.path to Shader(it.stage, it.path, sourceBytes, hashHEX)
                }
                .collect(Collectors.toList())
                .toMap()
        })

        val taskSetup = ForkJoinPool.commonPool().submit(Callable {
            val programQueue = ConcurrentLinkedQueue<Program<Shader>>()

            val allSources = allSourcesPending.get()

            for (program in inputPrograms) {
                val computeShader = program.compute?.let {
                    allSources[it.path]!!
                }
                val rasterShaders = program.raster?.map {
                    allSources[it.path]!!
                }
                programQueue.add(Program(program.name, computeShader, rasterShaders))
            }

            programQueue
        })

        try {
            glfwInit()
            GLFWErrorCallback.createPrint(System.err).set()

            val threadCount = minOf(Runtime.getRuntime().availableProcessors(), (inputPrograms.size + 15) / 16)
            println("Using $threadCount threads.")
            var first = 0L

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

                val window =  glfwCreateWindow(640, 480, "", 0, first)
                if (first == 0L) {
                    first = window
                }
                window
            }

            val threads = windows.map { window ->
                window to ForkJoinPool.commonPool().submit{
                    MemoryStack.stackPush().use { stack ->
                        val strings = stack.mallocPointer(1)
                        val lengths = stack.mallocInt(1)

                        glfwMakeContextCurrent(window)
                        runCatching { GL.create() }
                        GL.createCapabilities()

                        fun createProgram(shaders: List<Shader>): Int {
                            val program = glCreateProgram()
                            val shaderIDs = shaders.map {
                                val shaderID = glCreateShader(it.stage.value)
                                strings.put(0, it.srcBytes)
                                lengths.put(0, it.srcBytes.limit())
                                glShaderSource(shaderID, strings, lengths)
                                shaderID
                            }
                            shaderIDs.forEach {
                                glCompileShader(it)
                            }
                            shaderIDs.forEach {
                                glAttachShader(program, it)
                            }
                            glLinkProgram(program)
                            shaderIDs.forEach {
                                glDetachShader(program, it)
                                glDeleteShader(it)
                            }
                            return program
                        }


                        fun checkCache(shaders: List<Shader>, outputName: String, outputPath: Path): Boolean {
                            if (!outputDir.exists()) {
                                return false
                            }
                            val cachePath = outputDir.resolve("$outputName.json")
                            if (!cachePath.exists()) {
                                return false
                            }

                            val cacheInfo = runCatching {
                                Json.decodeFromString<CacheInfo>(cachePath.readText())
                            }.getOrNull() ?: return false

                            val outputHash = runCatching {
                                FileChannel.open(
                                    outputPath,
                                    StandardOpenOption.READ
                                ).use { channel ->
                                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                                        .order(ByteOrder.nativeOrder())
                                    hashBytes(mapped)
                                }
                            }.getOrNull()

                            if (outputHash != cacheInfo.outputHash) {
                                return false
                            }

                            val inputHashesMatch = shaders.all {
                                val expectedHash = cacheInfo.inputHashes[it.stage]
                                expectedHash != null && expectedHash == it.hash
                            }

                            return inputHashesMatch
                        }

                        fun createProgramAndDump(shaders: List<Shader>, outputName: String) {
                            val outputPath = outputDir.resolve("$outputName.txt")
                            if (checkCache(shaders, outputName, outputPath)) {
                                return
                            }
                            val programID = createProgram(shaders)

                            val binLen = glGetProgrami(programID, GL_PROGRAM_BINARY_LENGTH)
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
                                mapped.clear()
                                val outputHash = hashBytes(mapped)
                                val inputHashes = shaders.associate { it.stage to it.hash }
                                val cacheInfo = CacheInfo(outputHash, inputHashes)
                                val cachePath = outputDir.resolve("$outputName.json")
                                cachePath.writeText(Json.encodeToString(CacheInfo.serializer(), cacheInfo))
                            }

                            glDeleteProgram(programID)
                        }

                        val programQueue = taskSetup.get()
                        var program = programQueue.poll()
                        while (program != null) {
                            if (program.compute != null) {
                                createProgramAndDump(
                                    listOf(program.compute),
                                    "${program.name}_compute"
                                )
                            }

                            if (program.raster != null) {
                                createProgramAndDump(
                                    program.raster,
                                    "${program.name}_raster"
                                )
                            }
                            program = programQueue.poll()
                        }
                    }
                }
            }

            threads.forEach {
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