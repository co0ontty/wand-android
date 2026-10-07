package com.wand.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AD-02 主题色对回归：对比度纯函数 + 亮/暗两套 ColorScheme 的语义色对。
 * 全部数值来自实现的计算预期，不是设备采样；修复前的实测采样在
 * output/android-im-entry-dark-20261006/contrast-samples.json，修复后的设备复测走集成 Beta。
 * 门槛按角色判定（正文 4.5:1、合格大文本/图形 3:1、禁用态豁免），见 [WandContrastRole]。
 */
class WandThemeContrastTest {

    private fun assertRatio(label: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = wandContrast(foreground, background)
        assertTrue(
            "$label：${format(foreground)} on ${format(background)} = ${"%.2f".format(ratio)}:1，低于 ${minimum}:1",
            ratio >= minimum,
        )
    }

    private fun format(color: Color): String = color.toArgb().let { "#%06X".format(it and 0xFFFFFF) }

    /** 8bit 取整允许 1 LSB 误差：合成软底与报告采样值可能差最后一位。 */
    private fun assertArgbCloseTo(expected: Color, actual: Color) {
        val e = expected.toArgb()
        val a = actual.toArgb()
        for (shift in 8..24 step 8) {
            val delta = ((e ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)
            assertTrue("通道 0x%02X 差 $delta".format(shift), kotlin.math.abs(delta) <= 1)
        }
    }

    // —— 外观模式 × 系统深浅（D01）——

    @Test
    fun forcedModesAreNotOverriddenByTheSystemSwitch() {
        listOf(true, false).forEach { systemDark ->
            assertFalse("强制 Light 不被系统深色翻过去", wandResolveDark(WandAppearanceMode.Light, systemDark))
            assertTrue("强制 Dark 不被系统浅色翻过去", wandResolveDark(WandAppearanceMode.Dark, systemDark))
            assertTrue("System 档才跟随系统", systemDark == wandResolveDark(WandAppearanceMode.System, systemDark))
        }
    }

    @Test
    fun everyModeAndSystemCombinationGivesReadableDefaultInk() {
        // 三档模式 × 系统两种深浅共六种输入：解析出的模式必须给出与页面底可读的默认内容色。
        WandAppearanceMode.entries.forEach { mode ->
            listOf(true, false).forEach { systemDark ->
                val dark = wandResolveDark(mode, systemDark)
                val ink = wandDefaultContentColor(dark)
                val page = wandColorScheme(dark).background
                assertRatio("$mode(systemDark=$systemDark) 默认内容色/页面底", ink, page, 4.5)
            }
        }
    }

    // —— 纯函数 ——

    @Test
    fun relativeLuminanceMatchesWcagEndpoints() {
        assertEquals(0.0, wandRelativeLuminance(Color.Black), 1e-9)
        assertEquals(1.0, wandRelativeLuminance(Color.White), 1e-9)
        // 中灰 #808080 的线性亮度约 0.216，不是 0.5——这正是它白字只有约 4.6:1 的原因。
        assertEquals(0.216, wandRelativeLuminance(Color(0xFF808080)), 0.01)
    }

    @Test
    fun contrastIsSymmetricAndBlackOnWhiteIsTwentyOne() {
        assertEquals(21.0, wandContrast(Color.Black, Color.White), 1e-6)
        assertEquals(21.0, wandContrast(Color.White, Color.Black), 1e-6)
        assertEquals(1.0, wandContrast(Color.White, Color.White), 1e-6)
    }

    @Test
    fun translucentForegroundIsMeasuredAfterCompositing() {
        // 50% 白叠纯黑 = 实际灰，对比度必须按合成后的颜色算，不能当成纯白。
        val half = Color.White.copy(alpha = 0.5f)
        assertEquals(wandContrast(Color(0xFF808080), Color.Black), wandContrast(half, Color.Black), 0.05)
        assertTrue(wandContrast(half, Color.Black) < wandContrast(Color.White, Color.Black))
        assertEquals(Color.White, wandComposite(Color.White, Color.Black))
    }

    @Test
    fun solidInkPushesToThePoleThatActuallyClearsFourAndHalf() {
        // 极亮 / 极暗的底：两种候选墨本来就够，规则应原样返回，不推到纯黑纯白去「刷存在感」。
        assertEquals(Color(0xFFFFF7E6), wandSolidInkFor(Color.Black, Color(0xFFFFF7E6), Color(0xFF28231F)))
        assertEquals(Color(0xFF28231F), wandSolidInkFor(Color.White, Color(0xFFFFF7E6), Color(0xFF28231F)))
        // 中明度品牌橙是这一单的要点：白字 3.96 / 深墨 3.93 都不够，只有继续压深的墨能到 4.5。
        // 因此不能只比初始对比度取「更强的那一边」就收工（那会停在 3.96 并报成通过）。
        val brand = Color(0xFFC5653D)
        val ink = wandSolidInkFor(brand, Color(0xFFFFF7E6), Color(0xFF28231F))
        assertRatio("浅色 品牌实心容器/正文前景", ink, brand, 4.5)
        assertTrue("推进应是少量压深，不该一路退到纯黑", ink != Color.Black)
        assertTrue("确实推深了：比 textPrimary 更暗", wandRelativeLuminance(ink) < wandRelativeLuminance(Color(0xFF28231F)))
    }

    @Test
    fun textRoleIsDecidedByDeclaredSpNotStyleNameOrDensity() {
        // D05 的口径修正：14sp 的按钮正文（Material 样式名叫 labelLarge）与小号状态文字一律按正文 4.5:1；
        // sp 与 CSS px 对齐，只有显式字号达到 18pt(≈24sp) 或 14pt 粗体(≈18.7sp) 才算大字号。
        // 屏幕像素密度不参与判定：同一句 14sp 在 2× 与 3× 屏上都还是 Body。
        assertEquals(WandContrastRole.Body, wandTextRole(14f, 500))
        assertEquals(WandContrastRole.Body, wandTextRole(14f, 600))
        assertEquals(WandContrastRole.Body, wandTextRole(12f, 700))
        assertEquals(WandContrastRole.Body, wandTextRole(18f, 700))
        assertEquals(WandContrastRole.LargeText, wandTextRole(18.7f, 700))
        assertEquals(WandContrastRole.LargeText, wandTextRole(24f, 400))
        assertEquals(4.5, wandContrastMinimum(WandContrastRole.Body), 1e-9)
        assertEquals(3.0, wandContrastMinimum(WandContrastRole.Graphic), 1e-9)
        // #767676 是白字正文的下边界；#949494 只剩约 3.03:1，够图形与大字号、不够正文。
        assertTrue(wandMeetsContrast(Color.White, Color(0xFF767676), WandContrastRole.Body))
        assertFalse(wandMeetsContrast(Color.White, Color(0xFF949494), WandContrastRole.Body))
        assertTrue(wandMeetsContrast(Color.White, Color(0xFF949494), WandContrastRole.Graphic))
    }

    @Test
    fun textColorForKeepsTheHueAndOnlyMovesLightness() {
        // 已经达标的 accent 原样返回，不为「统一」而改外观；不达标的沿墨极压深/提亮。
        val page = Color(0xFFF3E9D2)
        val surfaces = listOf(page, Color(0xFFE9DDBF))
        val alreadyReadable = Color(0xFF28231F)
        assertEquals(alreadyReadable, wandTextColorFor(alreadyReadable, surfaces, dark = false))
        val gold = Color(0xFFC28A20)
        val ink = wandTextColorFor(gold, surfaces, dark = false)
        assertTrue("金色小字必须被压深", wandRelativeLuminance(ink) < wandRelativeLuminance(gold))
        surfaces.forEach { assertRatio("浅色 派生金墨/$it", ink, it, 4.5) }
        // 同色相：三个通道只是整体朝黑缩（各自的相对比例在 8bit 取整内保持），不会把金推成别的色族。
        val g = listOf(gold.red, gold.green, gold.blue)
        val i = listOf(ink.red, ink.green, ink.blue)
        assertTrue("三通道都只变暗：$g → $i", i.zip(g).all { (a, b) -> a <= b + 1e-6 })
        assertTrue("通道大小关系不变（仍是同一色族）：$i", i[0] > i[1] && i[1] > i[2])
        assertEquals((g[0] / g[2]).toDouble(), (i[0] / i[2]).toDouble(), 0.3)
    }

    // —— 暗色：ACCEPTANCE.md 实测为纯黑的正文层级 ——

    @Test
    fun darkSchemeBodyRolesAreLightNotBlack() {
        val dark = wandColorScheme(dark = true)
        assertEquals(Color(0xFF101114), dark.background)
        assertEquals(Color(0xFFF4F5F7), dark.onBackground)
        assertEquals(Color(0xFFF4F5F7), dark.onSurface)
        assertRatio("暗色 页面背景/正文", dark.onBackground, dark.background, 4.5)
        assertRatio("暗色 卡片/正文", dark.onSurface, dark.surface, 4.5)
        assertRatio("暗色 次级卡片/次级正文", dark.onSurfaceVariant, dark.surfaceVariant, 4.5)
        assertRatio("暗色 浮层/正文", dark.onSurface, dark.surfaceContainerHighest, 4.5)
        // 选中行软底（品牌 24% 叠在页面底上）——实测采到的是合成后的 #41291E。
        val selected = wandComposite(dark.primary.copy(alpha = 0.24f), dark.background)
        assertRatio("暗色 选中行/正文", dark.onBackground, selected, 4.5)
    }

    @Test
    fun lightSchemeBodyRolesStayReadable() {
        val light = wandColorScheme(dark = false)
        assertRatio("亮色 页面背景/正文", light.onBackground, light.background, 4.5)
        assertRatio("亮色 卡片/正文", light.onSurface, light.surface, 4.5)
        assertRatio("亮色 次级卡片/次级正文", light.onSurfaceVariant, light.surfaceVariant, 4.5)
        assertRatio("亮色 浮层/正文", light.onSurface, light.surfaceContainerHighest, 4.5)
        val selected = wandComposite(light.primary.copy(alpha = 0.09f), light.background)
        assertRatio("亮色 选中行/正文", light.onBackground, selected, 4.5)
    }

    // —— 实心容器：按实际底色派生，不假设「暗色 = 白字」 ——

    @Test
    fun darkAccentButtonsUseDarkInkBecauseAccentsAreLightened() {
        val dark = wandColorScheme(dark = true)
        // 白字压在这两套提亮后的强调色上只有 2.2–3.3:1，所以派生结果是深色墨。
        assertEquals(Color(0xFF101114), dark.onPrimary)
        assertEquals(Color(0xFF101114), dark.onError)
        assertRatio("暗色 品牌按钮/按钮字", dark.onPrimary, dark.primary, 4.5)
        assertRatio("暗色 危险按钮/按钮字", dark.onError, dark.error, 4.5)
        assertRatio("暗色 次级容器/字", dark.onSecondary, dark.secondary, 4.5)
        assertTrue(wandContrast(Color.White, dark.error) < 3.0)
        assertTrue(wandContrast(Color.White, dark.primary) < 3.5)
    }

    @Test
    fun lightAccentButtonsUseReadableInkOnTheDeeperBrandColor() {
        val light = wandColorScheme(dark = false)
        // 品牌色压深后使用浅色墨；不延续旧橙色只能用深墨的假设，仍按正文4.5:1核对。
        assertRatio("亮色 品牌按钮/按钮字", light.onPrimary, light.primary, 4.5)
        assertRatio("亮色 危险按钮/按钮字", light.onError, light.error, 4.5)
        assertRatio("亮色 次级容器/字", light.onSecondary, light.secondary, 4.5)
        assertEquals(light.surface, light.onPrimary)
    }

    @Test
    fun disabledInkStaysQuieterThanTheEnabledOne() {
        // 禁用控件在 WCAG 1.4.3 里豁免，这里要的是两件事：读得出字（≥3.0），
        // 且不得比可用态更醒目。上一版按「最高对比」派生，禁用字反而冲到 8.9 / 9.7:1。
        listOf(
            Triple("亮色", Color(0xFFC5653D), Color(0xFFF3E9D2)),
            Triple("暗色", Color(0xFFD47550), Color(0xFF13110F)),
        ).forEach { (name, accent, page) ->
            val disabledContainer = wandComposite(accent.copy(alpha = 0.34f), page)
            val enabledInk = if (name == "暗色") {
                wandSolidInkFor(accent, Color(0xFFF3EEE7), Color(0xFF13110F))
            } else {
                wandSolidInkFor(accent, Color(0xFFFFF7E6), Color(0xFF28231F))
            }
            val muted = if (name == "暗色") Color(0xFFC7BEB4) else Color(0xFF625A53)
            val enabledRatio = wandContrast(enabledInk, accent)
            val ink = wandDisabledInk(muted, disabledContainer, enabledRatio)
            val ratio = wandContrast(ink, disabledContainer)
            assertTrue("$name 禁用字仍读得出：$ratio", ratio >= 3.0)
            assertTrue("$name 禁用字不越过可用态（$ratio vs $enabledRatio）", ratio <= enabledRatio + 0.01)
            assertTrue("$name 禁用字比原始次级墨更淡或相等（没被推深）", ink == muted || wandContrast(ink, disabledContainer) < wandContrast(muted, disabledContainer) + 0.01)
        }
    }

    @Test
    fun softAccentContainerLabelIsTheTextInkNotTheRawAccent() {
        // onPrimaryContainer 承载的是软底胶囊上的文字，按正文 4.5:1 走派生墨色；
        // 原 accent 留给圆点 / 图标 / 描边那 3:1 的图形通道（见 accentStillCarriesGraphicRoles）。
        listOf(
            false to wandColorScheme(false).surface,
            true to wandColorScheme(true).surface,
        ).forEach { (dark, surface) ->
            val scheme = wandColorScheme(dark)
            val container = wandComposite(scheme.primaryContainer, surface)
            assertRatio("软底胶囊标签字（dark=$dark）", scheme.onPrimaryContainer, container, 4.5)
        }
    }

    @Test
    fun accentStillCarriesGraphicRolesAtTheThreeToOneBar() {
        // 背景 / 状态点 / 图标 / 描边继续用原 accent：图形门槛 3:1，外观不变。
        listOf(false, true).forEach { dark ->
            val scheme = wandColorScheme(dark)
            assertRatio(
                "品牌图标/页面底（dark=$dark）",
                scheme.primary,
                scheme.background,
                WAND_LARGE_OR_GRAPHIC_CONTRAST,
            )
        }
    }

    // —— 不退化：正文层级之间仍要留出差级，且固定终端面不被主题反色 ——

    @Test
    fun textHierarchyStaysMonotonicInBothModes() {
        listOf(false, true).forEach { dark ->
            val scheme = wandColorScheme(dark)
            val page = scheme.background
            val primary = wandContrast(scheme.onBackground, page)
            val secondary = wandContrast(scheme.onSurfaceVariant, scheme.surfaceVariant)
            val muted = wandContrast(mutedOf(dark), page)
            assertTrue("正文要比次级更醒目（$dark）：$primary vs $secondary", primary > secondary)
            assertTrue("次级要比弱文本更醒目（$dark）：$secondary vs $muted", secondary > muted)
            assertTrue("弱文本 / 占位仍达正文门槛（$dark）：$muted", muted >= 4.5)
        }
    }

    private fun mutedOf(dark: Boolean): Color =
        if (dark) Color(0xFF989BA5) else Color(0xFF6A6D75)

    @Test
    fun everyAccentTextInkClearsBodyContrastOnEverySurface() {
        // 七种语义 accent × 两套主题 × 六种文字底，全部按正文 4.5:1 遍历。
        // 这是 D05「举一反三」那一家族的回归门：以前状态标签按 3:1 记过，等于把小字当图形放过。
        listOf(false, true).forEach { dark ->
            val surfaces = wandTextSurfaces(dark)
            assertTrue("每种主题都要有六种文字底", surfaces.size >= 6)
            WandAccent.entries.forEach { accent ->
                val ink = wandAccentTextInk(accent, dark)
                surfaces.forEachIndexed { index, surface ->
                    assertRatio(
                        "${if (dark) "暗色" else "浅色"} ${accent.name} 小字 / 第 $index 种底",
                        ink,
                        surface,
                        4.5,
                    )
                }
            }
        }
    }

    @Test
    fun everyAccentClearsGraphicContrastAndKeepsTextInkOnItsOwnChannel() {
        // 实际主题的所有图形色都达到3:1，包括本轮同步压深的权限金色；文字通道另测4.5:1。
        listOf(false, true).forEach { dark ->
            val page = wandColorScheme(dark).background
            WandAccent.entries.forEach { accent ->
                val raw = wandAccentColor(accent, dark)
                val graphic = wandContrast(raw, page)
                assertTrue("${accent.name} 作图形应 ≥3:1，实际 $graphic", graphic >= WAND_LARGE_OR_GRAPHIC_CONTRAST)
                if (!dark) {
                    val ink = wandAccentTextInk(accent, dark)
                    assertTrue(
                        "浅色 ${accent.name} 的墨色应是原色或压深值，实际 $raw → $ink",
                        wandRelativeLuminance(ink) <= wandRelativeLuminance(raw),
                    )
                }
            }
        }
        // 浅色主题里「等待授权」金色小字是这一家里最差的实测点：修复前 2.50:1（报告口径 ≥3:1 也不过）。
        assertTrue(
            wandContrast(Color(0xFFC28A20), Color(0xFFF3E9D2)) < WAND_BODY_CONTRAST,
        )
    }

    @Test
    fun darkModeKeepsItsAccentsBecauseTheyAlreadyPassAsText() {
        // 派生规则不该为了统一而改外观：暗色主题七个 accent 原色本就 ≥4.5:1，墨色即原色。
        WandAccent.entries.forEach { accent ->
            val expected = wandAccentColor(accent, dark = true)
            val ink = wandAccentTextInk(accent, dark = true)
            assertTrue("${accent.name} 暗色墨色应等于已达标的原 accent", ink == expected)
        }
    }

    /**
     * 矩阵（results/theme/contrast-matrix.txt）里抄出去的那批派生取值，必须仍由 token 现场算出同一个值。
     * 允许 ±1 个 8bit 色级：推进步长落在 half-precision 上，最后一位可能差 1。
     */
    @Test
    fun derivedInksMatchTheMatrixValues() {
        assertArgbCloseTo(Color(0xFFFFFFFF), wandColorScheme(false).onPrimary)
        assertArgbCloseTo(Color(0xFFFFFFFF), wandColorScheme(false).onError)
        assertArgbCloseTo(Color(0xFFFFFFFF), wandColorScheme(false).onSecondary)
        assertArgbCloseTo(Color(0xFF101114), wandColorScheme(true).onPrimary)
        assertArgbCloseTo(Color(0xFF101114), wandColorScheme(true).onError)
        val lightExpected = mapOf(
            WandAccent.Brand to 0xFF0067BB,
            WandAccent.Success to 0xFF487051,
            WandAccent.Warning to 0xFF905B28,
            WandAccent.Danger to 0xFFA74A41,
            WandAccent.Permission to 0xFF895D13,
            WandAccent.Info to 0xFF45689B,
            WandAccent.Thinking to 0xFF636292,
        )
        lightExpected.forEach { (accent, argb) ->
            assertArgbCloseTo(Color(argb.toLong()), wandAccentTextInk(accent, dark = false))
        }
        assertArgbCloseTo(Color(0xFF63B4FF), wandAccentTextInk(WandAccent.Brand, dark = true))
    }

    @Test
    fun terminalSurfaceIsNotInvertedByTheTheme() {
        // PTY / diff 的固定暖深色面不跟随亮暗：正文色本身就是浅色，两种主题下都够读。
        assertRatio("终端 固定深底/正文", WandTerminal.text, WandTerminal.background, 4.5)
        assertRatio("终端 固定深底/次级", WandTerminal.muted, WandTerminal.background, 4.5)
        assertRatio("终端 固定深底/错误", WandTerminal.error, WandTerminal.background, 3.0)
    }

    // —— 主题默认内容色：ACCEPTANCE.md 那批黑字的直接成因 ——

    @Test
    fun defaultContentColorIsTheSchemeInkNotComposeBlack() {
        // material3 的 MaterialTheme 只提供 colors/typography/shapes，不提供 LocalContentColor，
        // 后者的静态默认是 Color.Black；页面根容器又多用 Modifier.background()（不带 contentColor）。
        // WandTheme 必须自己把 onBackground 给出去，否则裸 Text / Icon 停在纯黑。
        val dark = wandDefaultContentColor(dark = true)
        val light = wandDefaultContentColor(dark = false)
        assertTrue(dark != Color.Black)
        assertTrue(light != Color.Black)
        assertEquals(wandColorScheme(true).onBackground, dark)
        assertEquals(wandColorScheme(false).onBackground, light)
        listOf(
            "页面底" to Color(0xFF101114),
            "卡片底" to Color(0xFF211E1A),
            "浮层底" to Color(0xFF1D1A17),
            "次级卡片底" to Color(0xFF2A2621),
        ).forEach { (name, container) ->
            assertRatio("暗色 $name/默认内容色", dark, container, 4.5)
        }
    }

    @Test
    fun reproducesTheSampledFailuresFromTheAcceptanceReport() {
        // ACCEPTANCE.md 的 sRGB 采样必须能用同一套公式复现，否则说明计算口径不一致。
        val page = Color(0xFF13110F)
        val selectedRow = wandComposite(Color(0xFFD47550).copy(alpha = 0.24f), page)
        // 合成结果按 8bit 取整核对，与报告采到的 #41291E 相差 1 LSB 以内（浮点通道不做精确相等）。
        assertArgbCloseTo(Color(0xFF41291E), selectedRow)
        assertEquals(1.11, wandContrast(Color.Black, page), 0.01)
        assertEquals(1.56, wandContrast(Color.Black, selectedRow), 0.01)
        assertEquals(14.38, wandContrast(Color(0xFFF3EEE7), Color(0xFF211E1A)), 0.01)
        assertEquals(5.64, wandContrast(Color(0xFF958B81), page), 0.01)
        // 修复后同一批点位取默认内容色。
        assertRatio("暗色 群标题/页面底（修复后）", wandDefaultContentColor(true), page, 4.5)
        assertRatio("暗色 近期会话名/选中行（修复后）", wandDefaultContentColor(true), selectedRow, 4.5)
    }

    @Test
    fun paletteDidNotDrift() {
        val light = wandColorScheme(dark = false)
        val dark = wandColorScheme(dark = true)
        assertEquals(Color(0xFFF2F3F7), light.background)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFF1C1D21), light.onBackground)
        assertEquals(Color(0xFF0076D6), light.primary)
        assertEquals(Color(0xFF101114), dark.background)
        assertEquals(Color(0xFF1C1D21), dark.surface)
        assertEquals(Color(0xFF63B4FF), dark.primary)
    }
}
