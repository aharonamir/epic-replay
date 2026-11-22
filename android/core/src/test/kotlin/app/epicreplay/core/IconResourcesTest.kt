package app.epicreplay.core

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 004 section 5.4, FR-ICON-4: the Android resources of the icon are the SVGs of `shared/assets/brand/`, so a changed SVG fails here until
 * `android/tools/make_icon_resources.py` is run again. The path data of each drawable is compared with the SVG's, text for text.
 */
class IconResourcesTest {
    private val shared = File(System.getProperty("shared.dir"))
    private val android = File(System.getProperty("android.dir"))
    private val res = File(android, "app/src/main/res")
    private fun svg(name: String) = File(shared, "assets/brand/$name").readText()
    private fun xml(path: String) = File(res, path).readText()

    private class Layers(val background: String?, val paths: List<Pair<String, String>>)
    private fun fromSvg(text: String) = Layers(Regex("""<rect[^>]*fill="(#[0-9A-Fa-f]{6})"""").find(text)?.groupValues?.get(1), Regex("""<path fill="(#[0-9A-Fa-f]{6})" d="([^"]+)"""").findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList())
    private fun fromXml(text: String) = Regex("""android:fillColor="(#[0-9A-Fa-f]{6})" android:pathData="([^"]+)"""").findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()

    @Test
    fun theAdaptiveIconsLayersAreThePathsOfTheSvgs() {
        assertEquals(fromSvg(svg("icon-c-foreground.svg")).paths, fromXml(xml("drawable/ic_launcher_foreground.xml")))
        assertEquals(fromSvg(svg("icon-monochrome.svg")).paths, fromXml(xml("drawable/ic_launcher_monochrome.xml")))
        val adaptive = xml("mipmap-anydpi/ic_launcher.xml")
        assertTrue("@drawable/ic_launcher_foreground" in adaptive && "@drawable/ic_launcher_monochrome" in adaptive && "@color/ic_launcher_background" in adaptive)
    }

    @Test
    fun theSplashIconAndTheTopBarMarkAreAsPathsOfVariantAAndTheBackgroundsAreTheSvgsFills() {
        val a = fromSvg(svg("icon-a-dark.svg"))
        assertEquals(a.paths, fromXml(xml("drawable/ic_splash.xml")))
        assertEquals(a.paths.take(1), fromXml(xml("drawable/ic_mark.xml")))
        val colours = xml("values/ic_brand_colors.xml")
        assertTrue("""name="ic_launcher_background">${fromSvg(svg("icon-c-yellow.svg")).background}<""" in colours)
        assertTrue("""name="ic_splash_background">${a.background}<""" in colours)
        assertEquals("#FFD23F", fromSvg(svg("icon-c-yellow.svg")).background); assertEquals("#0D1012", a.background) // the values the spec names
    }

    @Test
    fun everyPointOfTheMarkAndTheLettersSitsInsideTheCircleOfRadiusThirtyThree() {
        for (name in listOf("icon-c-foreground.svg", "icon-monochrome.svg", "icon-a-dark.svg")) {
            for ((_, d) in fromSvg(svg(name)).paths) {
                var x = 0.0; var y = 0.0
                var cmd = ' '
                val toks = Regex("""[MLQVHZ]|-?\d+(?:\.\d+)?""").findAll(d).map { it.value }.toList()
                var i = 0
                while (i < toks.size) {
                    if (toks[i][0].isLetter()) { cmd = toks[i][0]; i++; if (cmd == 'Z') continue }
                    val n = when (cmd) { 'M', 'L' -> 2; 'Q' -> 4; else -> 1 }
                    val v = toks.subList(i, i + n).map { it.toDouble() }
                    when (cmd) { 'H' -> x = v[0]; 'V' -> y = v[0]; else -> { for (k in 0 until n step 2) assertTrue(hypot(v[k] - 54, v[k + 1] - 54) <= 33.0 + 1e-6, "$name: point ${v[k]},${v[k + 1]} is outside the safe circle"); x = v[n - 2]; y = v[n - 1] } }
                    if (cmd == 'H' || cmd == 'V') assertTrue(hypot(x - 54, y - 54) <= 33.0 + 1e-6, "$name: point $x,$y is outside the safe circle")
                    i += n
                    if (cmd == 'M') cmd = 'L'
                }
            }
        }
    }

    @Test
    fun theStoreImageIs512BySquareOfVariantCWithTheMarkAndTheLettersWhereTheSvgHasThem() {
        val img = ImageIO.read(File(android, "store/icon-512.png"))
        assertEquals(512, img.width); assertEquals(512, img.height)
        fun px(x: Double, y: Double) = img.getRGB((x * 512 / 108).toInt(), (y * 512 / 108).toInt()) and 0xFFFFFF
        assertEquals(0xFFD23F, px(1.0, 1.0)); assertEquals(0xFFD23F, px(107.0, 107.0)); assertEquals(0xFFD23F, px(8.0, 95.0)) // the whole square is the tile, corners included
        assertEquals(0x0D1012, px(48.8, 36.0)) // inside the left triangle
        assertEquals(0x0D1012, px(66.0, 36.0)) // inside the right triangle
        assertEquals(0x0D1012, px(40.5, 70.0)) // the stem of the E
        assertEquals(0xFFD23F, px(47.0, 63.5)) // between the E's top and middle arms: the gap is the tile again
    }

    @Test
    fun theManifestHasTheIconAndTheSplashTheme() {
        val manifest = File(android, "app/src/main/AndroidManifest.xml").readText()
        assertTrue("""android:icon="@mipmap/ic_launcher"""" in manifest && """android:theme="@style/Theme.EpicReplay.Splash"""" in manifest)
        val themes = xml("values/themes.xml")
        assertTrue("@drawable/ic_splash" in themes && "@color/ic_splash_background" in themes && "postSplashScreenTheme" in themes)
    }
}
