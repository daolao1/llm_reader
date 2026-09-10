package com.zzy.llmreader

import com.zzy.llmreader.data.ImageBackend
import com.zzy.llmreader.data.ImageGenSettings
import com.zzy.llmreader.image.StChatu8Importer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * st-chatu8 配置导入的解析测试。
 *
 * 这块逻辑原先埋在 3144 行的 ReaderViewModel 里、完全没法测；
 * 抽成 [StChatu8Importer] 这个纯函数后可以直接覆盖各种输入形态。
 */
class StChatu8ImporterTest {

    private val base = ImageGenSettings()

    @Test
    fun `解析平铺的 st-chatu8 配置`() {
        val json = """
            {
              "client": "novelai",
              "novelaiApi": "pst-abc123",
              "novelaimode": "nai-diffusion-4-5-full",
              "novelai_steps": "28",
              "novelai_width": "832",
              "nai3Scale": "7.5",
              "nai3Variety": "true"
            }
        """.trimIndent()

        val r = StChatu8Importer.importFromText(json, base)

        assertEquals(ImageBackend.NOVELAI, r.settings.backend)
        assertEquals("pst-abc123", r.settings.novelaiApiKey)
        assertEquals("nai-diffusion-4-5-full", r.settings.novelaiModel)
        assertEquals(28, r.settings.novelaiSteps)
        assertEquals(832, r.settings.novelaiWidth)
        assertEquals(7.5, r.settings.novelaiScale)
        assertTrue(r.settings.novelaiVarietyBoost)
    }

    @Test
    fun `解析整份 SillyTavern settings 里嵌套的 st-chatu8`() {
        val json = """
            {
              "someOtherExtension": { "foo": 1 },
              "extension_settings": {
                "st-chatu8": { "client": "comfyui", "comfyuiUrl": "http://127.0.0.1:8188" }
              }
            }
        """.trimIndent()

        val r = StChatu8Importer.importFromText(json, base)

        assertEquals(ImageBackend.COMFYUI, r.settings.backend)
        assertEquals("http://127.0.0.1:8188", r.settings.comfyuiUrl)
    }

    @Test
    fun `jiuguan 代理按 ComfyUI 处理`() {
        val r = StChatu8Importer.importFromText("""{"client":"jiuguan"}""", base)
        assertEquals(ImageBackend.COMFYUI, r.settings.backend)
    }

    @Test
    fun `未指定 client 时保留已有后端，NONE 则默认 NovelAI`() {
        val kept = StChatu8Importer.importFromText("{}", base.copy(backend = ImageBackend.COMFYUI))
        assertEquals(ImageBackend.COMFYUI, kept.settings.backend, "已有后端不应被覆盖")

        val defaulted = StChatu8Importer.importFromText("{}", base.copy(backend = ImageBackend.NONE))
        assertEquals(ImageBackend.NOVELAI, defaulted.settings.backend)
    }

    @Test
    fun `缺省字段保留原值而不是清空`() {
        val cur = base.copy(novelaiApiKey = "keep-me", novelaiModel = "my-model")
        val r = StChatu8Importer.importFromText("""{"client":"novelai"}""", cur)
        assertEquals("keep-me", r.settings.novelaiApiKey)
        assertEquals("my-model", r.settings.novelaiModel)
    }

    @Test
    fun `字典形式的角色与装束预设`() {
        val json = """
            {
              "characterPresets": {
                "爱丽丝": {
                  "english": "Alice",
                  "aliases": ["小爱", "Alice"],
                  "appearance": "1girl, blonde hair",
                  "defaultOutfit": "校服"
                }
              },
              "outfitPresets": {
                "校服": { "prompt": "school uniform", "notes": "夏装" }
              }
            }
        """.trimIndent()

        val r = StChatu8Importer.importFromText(json, base)

        assertEquals(1, r.library.characters.size)
        val c = r.library.characters.first()
        assertEquals("爱丽丝", c.name)
        assertEquals("Alice", c.englishName)
        assertEquals(listOf("小爱", "Alice"), c.aliases)
        assertEquals("1girl, blonde hair", c.appearancePrompt)
        assertEquals("校服", c.defaultOutfitId)

        assertEquals(1, r.library.outfits.size)
        assertEquals("school uniform", r.library.outfits.first().prompt)
    }

    @Test
    fun `列表形式的 jueselist 与 outfitslist`() {
        val json = """
            {
              "jueselist": [ { "name": "鲍勃", "appearance": "1boy" } ],
              "outfitslist": [ { "name": "便服", "prompt": "casual" } ]
            }
        """.trimIndent()

        val r = StChatu8Importer.importFromText(json, base)

        assertEquals("鲍勃", r.library.characters.single().name)
        assertEquals("便服", r.library.outfits.single().name)
    }

    @Test
    fun `无名字的角色与装束被过滤掉`() {
        val r = StChatu8Importer.importFromText(
            """{"characterPresets":{"":{"appearance":"x"}}}""",
            base,
        )
        assertTrue(r.library.characters.isEmpty())
    }

    @Test
    fun `workers 里只有含 class_type 的条目算工作流`() {
        val json = """
            {
              "client": "comfyui",
              "workerid": "我的工作流",
              "workers": {
                "links": "[1,2,3]",
                "last_link_id": "7",
                "我的工作流": "{\"3\":{\"class_type\":\"KSampler\"}}",
                "备用工作流": "{\"4\":{\"class_type\":\"CheckpointLoader\"}}"
              }
            }
        """.trimIndent()

        val r = StChatu8Importer.importFromText(json, base)

        assertEquals(2, r.workflowCount, "links / last_link_id 不应计入")
        assertEquals("我的工作流", r.settings.comfyuiWorkflowName)
        assertContains(r.settings.comfyuiWorkflowJson, "KSampler")
        assertContains(r.summary, "工作流 2 个")
    }

    @Test
    fun `workflowid 指向非工作流时回退到第一个可用工作流`() {
        val json = """
            {
              "workerid": "links",
              "workers": {
                "links": "[1,2]",
                "真正的流": "{\"5\":{\"class_type\":\"KSampler\"}}"
              }
            }
        """.trimIndent()

        val r = StChatu8Importer.importFromText(json, base)
        assertEquals("真正的流", r.settings.comfyuiWorkflowName)
    }

    @Test
    fun `非法 JSON 抛异常而不是静默失败`() {
        assertFailsWith<Throwable> {
            StChatu8Importer.importFromText("这不是 JSON", base)
        }
        // 合法 JSON 但不是对象
        assertFailsWith<IllegalStateException> {
            StChatu8Importer.importFromText("[1,2,3]", base)
        }
    }

    @Test
    fun `导入摘要反映实际内容`() {
        val json = """
            {
              "client": "novelai",
              "characterPresets": { "A": {"appearance":"x"} },
              "outfitPresets": { "B": {"prompt":"y"} }
            }
        """.trimIndent()

        val summary = StChatu8Importer.importFromText(json, base).summary
        assertContains(summary, "NOVELAI")
        assertContains(summary, "角色 1 个")
        assertContains(summary, "装束 1 个")
    }
}
