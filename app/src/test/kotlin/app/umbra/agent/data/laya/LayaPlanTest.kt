package app.umbra.agent.data.laya

import kotlin.test.Test
import kotlin.test.assertEquals

class LayaPlanTest {
    private val fp16 = LayaFile("laya-typed-decisions-fp16.tflite", "gpu", 1, "a")
    private val manifest = LayaManifest(
        "convaiinnovations/laya-typed-decisions", "1a793eb568e6", "https://example/",
        listOf(
            fp16,
            fp16.copy(backend = "cpu"),
            LayaFile("npu/qualcomm-SM8650.tflite", "npu", 1, "b", "qualcomm", "SM8650"),
            LayaFile("npu/mediatek-MT6989.tflite", "npu", 1, "c", "mediatek", "MT6989"),
            LayaFile("npu/google_tensor-Tensor_G4.tflite", "npu", 1, "d", "google_tensor", "Tensor_G4"),
        ),
        LayaCheck(256, listOf(1), listOf(0), 2, listOf(0f, 1f), mapOf("cpu" to 0.5f)),
    )
    private val all = setOf(LayaBackend.NPU, LayaBackend.GPU, LayaBackend.CPU)

    private fun plan(maker: String?, soc: String?, available: Set<LayaBackend> = all, refused: Set<String> = emptySet()) =
        LayaPlan.candidates(manifest, maker, soc, available, refused).map { it.backend to it.file.name }

    @Test fun theNpuComesFirstThenTheGpuThenTheCpu() {
        assertEquals(listOf(
            LayaBackend.NPU to "npu/qualcomm-SM8650.tflite",
            LayaBackend.GPU to fp16.name,
            LayaBackend.GPU to fp16.name,
            LayaBackend.CPU to fp16.name,
        ), plan("QTI", "SM8650"))
        assertEquals(LayaBackend.NPU to "npu/mediatek-MT6989.tflite", plan("Mediatek", "MT6989").first())
        assertEquals(LayaBackend.NPU to "npu/google_tensor-Tensor_G4.tflite", plan("Google", "Tensor G4").first())
    }

    @Test fun withoutAFileForItsSocQualcommCompilesOnThePhone() {
        val first = LayaPlan.candidates(manifest, "QTI", "SM7675", all, emptySet()).first()
        assertEquals(LayaCandidate(LayaBackend.NPU, fp16, onDevice = true), first)
        // MediaTek's and Google Tensor's NPUs run only files compiled ahead of time.
        assertEquals(LayaBackend.GPU, plan("Mediatek", "MT6897").first().first)
        assertEquals(LayaBackend.GPU, plan("Google", "Tensor G2").first().first)
        // A refused on-device compile is remembered apart from the GPU, which uses the same file.
        assertEquals(LayaBackend.GPU, plan("QTI", "SM7675", refused = setOf("npu:${fp16.name}")).first().first)
    }

    @Test fun withoutAnNpuRuntimeItStartsAtTheGpu() {
        assertEquals(LayaBackend.GPU, plan("QTI", "SM8650", available = setOf(LayaBackend.GPU, LayaBackend.CPU)).first().first)
        assertEquals(listOf(LayaBackend.CPU to fp16.name), plan(null, null, available = setOf(LayaBackend.CPU)))
    }

    @Test fun refusedAcceleratorsAreSkippedButTheCpuAlwaysRemains() {
        val refused = setOf("npu:npu/qualcomm-SM8650.tflite", "gpu:${fp16.name}", "gpu:${fp16.name}#opengl", "cpu:${fp16.name}")
        assertEquals(listOf(LayaBackend.CPU to fp16.name), plan("QTI", "SM8650", refused = refused))
    }

    @Test fun theGpuTriesOpenClThenOpenGlThenFp32WhereMemoryAllows() {
        val gpus = { roomy: Boolean -> LayaPlan.candidates(manifest, "Samsung", "s5e9945", all, emptySet(), roomy).filter { it.backend == LayaBackend.GPU } }
        assertEquals(listOf(GpuPath.AUTO, GpuPath.OPENGL), gpus(false).map { it.gpu })
        assertEquals(listOf(GpuPath.AUTO, GpuPath.OPENGL, GpuPath.FP32), gpus(true).map { it.gpu })
        // Each path is refused on its own, and the first keeps the key earlier versions refused it under.
        assertEquals(listOf("gpu:${fp16.name}", "gpu:${fp16.name}#opengl", "gpu:${fp16.name}#fp32"), gpus(true).map(LayaPlan::key))
        val afterOpenCl = LayaPlan.candidates(manifest, "Samsung", "s5e9945", all, setOf("gpu:${fp16.name}"), roomy = true)
        assertEquals(listOf(GpuPath.OPENGL, GpuPath.FP32), afterOpenCl.filter { it.backend == LayaBackend.GPU }.map { it.gpu })
    }

    @Test fun anExynosHasNoNpuFileSoItStartsAtItsGpu() {
        assertEquals(null, LayaPlan.vendor("Samsung", "s5e9945"))
        assertEquals(LayaBackend.GPU, plan("Samsung", "s5e9945").first().first)
    }

    @Test fun aHexagonV68FileComesWithItsTokenTableWhichIsNoCandidate() {
        val table = LayaFile("laya-embeddings-fp16.bin", "aux", 77_365_248, "e", shape = listOf(50_368, 768))
        val v68 = LayaFile("npu/qualcomm-SM8350.tflite", "npu", 1, "f", "qualcomm", "SM8350", embeddings = table.name)
        val withTable = manifest.copy(files = manifest.files + v68 + table)
        val candidates = LayaPlan.candidates(withTable, "QTI", "SM8350", all, emptySet())
        assertEquals(LayaCandidate(LayaBackend.NPU, v68), candidates.first())
        assertEquals(table.name, candidates.first().file.embeddings)
        kotlin.test.assertTrue(candidates.none { it.file.backend == "aux" })
        // 888+ reports SM8350 with a letter after it; 778G+ is SM7325-AE.
        assertEquals(LayaBackend.NPU, LayaPlan.candidates(withTable, "QTI", "SM8350P", all, emptySet()).first().backend)
    }

    @Test fun aVariantOfTheSocUsesItsFileButAnotherChipDoesNot() {
        assertEquals(LayaBackend.NPU to "npu/mediatek-MT6989.tflite", plan("Mediatek", "MT6989Z").first())
        assertEquals(LayaBackend.NPU to "npu/mediatek-MT6989.tflite", plan("Mediatek", "MT6989Z/CZA").first())
        assertEquals(LayaBackend.GPU, plan("Mediatek", "MT69891").first().first)
        assertEquals(LayaBackend.GPU, plan("Mediatek", "MT698").first().first)
    }

    @Test fun aFileWithItsOwnUrlIsFetchedFromThere() {
        assertEquals("https://example/laya-typed-decisions-fp16.tflite", fp16.source(manifest))
        val npu = LayaFile("npu/qualcomm-SM8650.tflite", "npu", 1, "b", "qualcomm", "SM8650", url = "https://github.com/x/releases/download/t/qualcomm-SM8650.tflite")
        assertEquals(npu.url, npu.source(manifest))
    }

    @Test fun aGraphWithOnlyTheLongLengthGetsShortRowsPaddedUp() {
        var seen: List<Any>? = null
        val inner = object : app.umbra.core.decision.LayaGraph {
            override val backend = "npu"
            override fun run(length: Int, inputIds: IntArray, keyMask: FloatArray, markers: FloatArray, qtype: FloatArray): FloatArray {
                seen = listOf(length, inputIds.toList(), keyMask.toList(), markers.toList(), qtype.toList())
                return floatArrayOf(1f)
            }
            override fun close() = Unit
        }
        val graph = PaddedGraph(inner, listOf(4), pad = 9)
        // Two options over two tokens: option 0 marks token 0, option 1 marks token 1.
        graph.run(2, intArrayOf(5, 6), floatArrayOf(0f, 0f), floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 0f))
        val m = app.umbra.core.decision.LayaInputs.MASKED
        assertEquals(listOf(4, listOf(5, 6, 9, 9), listOf(0f, 0f, m, m), listOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f), listOf(0f, 1f, 0f)), seen)
        // A length it has goes through unchanged.
        graph.run(4, intArrayOf(1, 2, 3, 4), FloatArray(4), FloatArray(8), FloatArray(3))
        assertEquals(4, seen!![0])
    }

    @Test fun vendorsAreReadFromTheSocFields() {
        assertEquals("qualcomm", LayaPlan.vendor("QTI", "SM8750"))
        assertEquals("mediatek", LayaPlan.vendor("Mediatek", "MT6991"))
        assertEquals("google_tensor", LayaPlan.vendor("Google", "Tensor G3"))
        assertEquals(null, LayaPlan.vendor("Samsung", "s5e9945"))
        assertEquals(LayaPlan.normalize("Tensor_G3"), LayaPlan.normalize("Tensor G3"))
    }
}
