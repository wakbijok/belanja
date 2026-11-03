package uk.wakbijok.belanja.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.wakbijok.belanja.settings.SettingsRepository

class MuraiVoiceTest {

    @Test
    fun `each dial is a different person`() {
        val sopan = MuraiVoice.system(SettingsRepository.MAKCIK_SOPAN, MuraiVoice.Lane.ROAST, 75)
        val murai = MuraiVoice.system(SettingsRepository.MAKCIK_MAKCIK, MuraiVoice.Lane.ROAST, 75)
        val lazer = MuraiVoice.system(SettingsRepository.MAKCIK_PEDAS, MuraiVoice.Lane.ROAST, 75)
        assertTrue(sopan.contains("Suara: SOPAN"))
        assertTrue(murai.contains("Suara: MURAI"))
        assertTrue(lazer.contains("Suara: LAZER"))
        assertNotEquals(sopan, murai)
        assertNotEquals(murai, lazer)
    }

    @Test
    fun `the percentage sets bite but never swaps the voice`() {
        val calm = MuraiVoice.system(SettingsRepository.MAKCIK_SOPAN, MuraiVoice.Lane.ROAST, 72)
        val broken = MuraiVoice.system(SettingsRepository.MAKCIK_SOPAN, MuraiVoice.Lane.ROAST, 108)
        assertTrue(calm.contains("Suara: SOPAN") && broken.contains("Suara: SOPAN"))
        assertTrue(calm.contains("(72%)"))
        assertTrue(broken.contains("DAH PECAH (108%)"))
        assertTrue(broken.contains("kekal dalam suara kau"))
    }

    @Test
    fun `bebel lane carries no warning and unknown dials fall back to Murai`() {
        val s = MuraiVoice.system("whatever", MuraiVoice.Lane.BEBEL, 40)
        assertTrue(s.contains("Suara: MURAI"))
        assertTrue(s.contains("Tiada amaran"))
        assertFalse(s.contains("zon bahaya"))
    }

    @Test
    fun `ground rules forbid the tryhard tells`() {
        val s = MuraiVoice.system(SettingsRepository.MAKCIK_MAKCIK, MuraiVoice.Lane.ROAST, 91)
        assertTrue(s.contains("Tiada emoji"))
        assertTrue(s.contains("Angka WAJIB sama"))
        assertTrue(s.contains("bukan berlakon"))
    }
}
