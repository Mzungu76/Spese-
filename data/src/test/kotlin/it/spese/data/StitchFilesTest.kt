package it.spese.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StitchFilesTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() = env.close()

    private fun jpg(name: String, w: Int, h: Int): File {
        val f = File(env.context.cacheDir, name)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.GRAY)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    @Test fun `le foto si compongono in un unico documento lungo e le singole si possono eliminare`() = runBlocking {
        val a = env.files.saveImage(jpg("a.jpg", 600, 800).absolutePath)
        val b = env.files.saveImage(jpg("b.jpg", 600, 800).absolutePath)
        val long = env.files.stitch(listOf(a, b))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(env.files.pathOf(long.fileName), bounds)
        assertEquals(600, bounds.outWidth); assertTrue(bounds.outHeight in 1600..1620)
        env.files.deleteFile(a.fileName); env.files.deleteFile(b.fileName)
        assertTrue(File(env.files.pathOf(long.fileName)).exists()); assertFalse(File(env.files.pathOf(a.fileName)).exists())
    }

    @Test fun `con molte foto l altezza resta entro il limite`() = runBlocking {
        val refs = (1..6).map { env.files.saveImage(jpg("m$it.jpg", 1000, 2000).absolutePath) }
        val long = env.files.stitch(refs)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(env.files.pathOf(long.fileName), bounds)
        assertTrue("altezza ${bounds.outHeight}", bounds.outHeight <= 7000)
    }
}
