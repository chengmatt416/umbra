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
        val refused = setOf("npu:npu/qualcomm-SM8650.tflite", "gpu:${fp16.name}", "cpu:${fp16.name}")
        assertEquals(listOf(LayaBackend.CPU to fp16.name), plan("QTI", "SM8650", refused = refused))
    }

    @Test fun vendorsAreReadFromTheSocFields() {
        assertEquals("qualcomm", LayaPlan.vendor("QTI", "SM8750"))
        assertEquals("mediatek", LayaPlan.vendor("Mediatek", "MT6991"))
        assertEquals("google_tensor", LayaPlan.vendor("Google", "Tensor G3"))
        assertEquals(null, LayaPlan.vendor("Samsung", "s5e9945"))
        assertEquals(LayaPlan.normalize("Tensor_G3"), LayaPlan.normalize("Tensor G3"))
    }
}
