package net.pdd.comagic.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultModelTest {

    private fun model(id: String, provider: String = "p", name: String = "") =
        ModelInfo(id = id, providerID = provider, name = name)

    @Test
    fun prefersDeepSeekModel() {
        val models = listOf(model("glm-5.3"), model("deepseek-v4", provider = "deepseek"))
        assertEquals("deepseek-v4", pickDefaultModel(models)?.id)
    }

    @Test
    fun matchesDeepSeekProviderEvenIfIdIsOpaque() {
        val models = listOf(model("glm-5.3"), model("chat", provider = "deepseek"))
        assertEquals("chat", pickDefaultModel(models)?.id)
    }

    @Test
    fun fallsBackToSessionModelThenFirst() {
        val glm = model("glm-5.3")
        val models = listOf(glm, model("other"))
        assertEquals("glm-5.3", pickDefaultModel(models, ModelRef(id = "glm-5.3", providerID = "p"))?.id)
        assertEquals("glm-5.3", pickDefaultModel(models)?.id)
        assertNull(pickDefaultModel(emptyList()))
    }
}
