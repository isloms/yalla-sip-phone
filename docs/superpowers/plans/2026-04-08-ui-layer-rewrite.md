# UI Layer Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the entire UI layer of Yalla SIP Phone — fix broken color semantics, extract a reusable component library, and rewrite every screen with proper Compose patterns.

**Architecture:** Bottom-up rewrite. First fix the design token foundation (YallaColors, AppTokens), then build reusable components in `ui/component/`, then rewrite each screen to use the new tokens and components. All popover/dropdown positioning will be anchored to their trigger elements instead of following the mouse cursor.

**Tech Stack:** Kotlin, Compose Desktop 1.8.2, Material3, Material Kolor 2.0.0, Decompose 3.4.0

**Constraint:** Dialogs and dropdowns MUST use `DialogWindow` (OS-level windows) because JCEF renders above regular Compose popups. This is intentional — do not replace with `Popup` or `DropdownMenu`.

---

## File Structure

### Files to Create
| File | Responsibility |
|------|---------------|
| `ui/theme/YallaColors.kt` | Rewrite — semantic color names, fix dark theme bug |
| `ui/theme/AppTokens.kt` | Rewrite — add alpha tokens, typography scale, component sizes |
| `ui/theme/Theme.kt` | Update — remove ExtendedColors, use new token names |
| `ui/component/YallaIconButton.kt` | Reusable icon button with consistent sizing/styling |
| `ui/component/YallaTooltip.kt` | Component-anchored tooltip (not cursor-following) |
| `ui/component/YallaDropdownWindow.kt` | Anchored DialogWindow dropdown with proper positioning |
| `ui/component/YallaSegmentedControl.kt` | Two-option segmented toggle |
| `feature/main/toolbar/CallActions.kt` | Rewrite — use YallaIconButton |
| `feature/main/toolbar/SipChipRow.kt` | Rewrite — use YallaTooltip, new tokens |
| `feature/main/toolbar/CallTimer.kt` | Rewrite — use new tokens |
| `feature/main/toolbar/PhoneField.kt` | Rewrite — use new tokens |
| `feature/main/toolbar/AgentStatusButton.kt` | Rewrite — use YallaDropdownWindow |
| `feature/main/toolbar/SettingsDialog.kt` | Rewrite — use YallaSegmentedControl, scrollable |
| `feature/main/toolbar/ToolbarContent.kt` | Update — use new color names |
| `feature/main/MainScreen.kt` | Update — use new color names |
| `feature/login/LoginScreen.kt` | Rewrite — remove forced dark theme hack |
| `navigation/RootContent.kt` | No change needed |

### Files to Create (Tests)
| File | What it tests |
|------|--------------|
| `test/.../ui/theme/YallaColorsTest.kt` | Dark theme elevation hierarchy, no duplicate values |
| `test/.../ui/theme/AppTokensTest.kt` | Alpha ranges, typography scale ordering |

### Files to Update (Existing Tests)
| File | Why |
|------|-----|
| Any test referencing old color field names | Rename to match new YallaColors fields |

---

## Task 1: Rewrite YallaColors — Semantic Names + Dark Theme Fix

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/ui/theme/YallaColors.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/ui/theme/YallaColorsTest.kt`

### Rename Mapping

These renames apply across the ENTIRE codebase. Every file that references the old name must be updated.

```
buttonActive    → (REMOVED — use brandPrimary instead, same value)
buttonDisabled  → surfaceMuted
iconDisabled    → brandLight
iconRed         → destructive
iconSubtle      → (keep — semantically fine)
pinkSun         → statusWarning
borderDisabled  → borderDefault
errorIndicator  → (REMOVED — duplicate of errorText)
```

### Dark Theme Bug Fix

Current dark theme `backgroundTertiary` (#1D1D26) is DARKER than `backgroundSecondary` (#21222B). Tertiary should be LIGHTER (more elevated). Fix: swap to #2A2B35.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/uz/yalla/sipphone/ui/theme/YallaColorsTest.kt`:

```kotlin
package uz.yalla.sipphone.ui.theme

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YallaColorsTest {

    @Test
    fun `dark theme surface hierarchy has increasing brightness`() {
        val dark = YallaColors.Dark
        // surfaceBase < surfaceSecondary < surfaceTertiary in perceived brightness
        val baseBrightness = brightness(dark.backgroundBase)
        val secondaryBrightness = brightness(dark.backgroundSecondary)
        val tertiaryBrightness = brightness(dark.backgroundTertiary)

        assertTrue(
            baseBrightness < secondaryBrightness,
            "backgroundBase ($baseBrightness) should be darker than backgroundSecondary ($secondaryBrightness)",
        )
        assertTrue(
            secondaryBrightness < tertiaryBrightness,
            "backgroundSecondary ($secondaryBrightness) should be darker than backgroundTertiary ($tertiaryBrightness)",
        )
    }

    @Test
    fun `light theme surface hierarchy has decreasing brightness`() {
        val light = YallaColors.Light
        val baseBrightness = brightness(light.backgroundBase)
        val secondaryBrightness = brightness(light.backgroundSecondary)
        val tertiaryBrightness = brightness(light.backgroundTertiary)

        assertTrue(
            baseBrightness > secondaryBrightness,
            "Light backgroundBase should be brighter than backgroundSecondary",
        )
        assertTrue(
            secondaryBrightness > tertiaryBrightness,
            "Light backgroundSecondary should be brighter than backgroundTertiary",
        )
    }

    @Test
    fun `brandPrimary has same value in both themes`() {
        // Brand purple should be consistent across themes
        assertTrue(YallaColors.Light.brandPrimary == YallaColors.Dark.brandPrimary)
    }

    /** Perceived brightness using relative luminance formula. */
    private fun brightness(color: androidx.compose.ui.graphics.Color): Float =
        0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.ui.theme.YallaColorsTest" --info`

Expected: FAIL — `dark theme surface hierarchy` fails because current `backgroundTertiary` is darker than `backgroundSecondary`. Also compile errors because `buttonActive` field no longer exists (we'll rename in step 3).

- [ ] **Step 3: Rewrite YallaColors.kt**

Replace the entire file `src/main/kotlin/uz/yalla/sipphone/ui/theme/YallaColors.kt`:

```kotlin
package uz.yalla.sipphone.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class YallaColors(
    // Brand
    val brandPrimary: Color,
    val brandPrimaryMuted: Color,
    val brandPrimaryText: Color,
    val brandLight: Color,
    // Backgrounds
    val backgroundBase: Color,
    val backgroundSecondary: Color,
    val backgroundTertiary: Color,
    // Text
    val textBase: Color,
    val textSubtle: Color,
    // Borders
    val borderDefault: Color,
    val borderStrong: Color,
    // Status
    val errorText: Color,
    val destructive: Color,
    val statusWarning: Color,
    // Surfaces
    val surfaceMuted: Color,
    // Icons
    val iconSubtle: Color,
    // Call states
    val callReady: Color,
    val callIncoming: Color,
    val callMuted: Color,
    val callOffline: Color,
    val callWrapUp: Color,
) {
    companion object {
        val Light = YallaColors(
            brandPrimary = Color(0xFF562DF8),
            brandPrimaryMuted = Color(0xFFC8CBFA),
            brandPrimaryText = Color(0xFF562DF8),
            brandLight = Color(0xFFC8CBFA),
            backgroundBase = Color(0xFFFFFFFF),
            backgroundSecondary = Color(0xFFF7F7F7),
            backgroundTertiary = Color(0xFFE9EAEA),
            textBase = Color(0xFF101828),
            textSubtle = Color(0xFF98A2B3),
            borderDefault = Color(0xFFE4E7EC),
            borderStrong = Color(0xFF101828),
            errorText = Color(0xFFF42500),
            destructive = Color(0xFFF42500),
            statusWarning = Color(0xFFFF234B),
            surfaceMuted = Color(0xFFF7F7F7),
            iconSubtle = Color(0xFF98A2B3),
            callReady = Color(0xFF2E7D32),
            callIncoming = Color(0xFFD97706),
            callMuted = Color(0xFFF42500),
            callOffline = Color(0xFF6B7280),
            callWrapUp = Color(0xFF7C3AED),
        )

        val Dark = YallaColors(
            brandPrimary = Color(0xFF562DF8),
            brandPrimaryMuted = Color(0xFF2C2D34),
            brandPrimaryText = Color(0xFF8B6FFF),
            brandLight = Color(0xFFC8CBFA),
            backgroundBase = Color(0xFF1A1A20),
            backgroundSecondary = Color(0xFF21222B),
            backgroundTertiary = Color(0xFF2A2B35), // FIXED: was #1D1D26 (darker than secondary)
            textBase = Color(0xFFFFFFFF),
            textSubtle = Color(0xFF747C8B),
            borderDefault = Color(0xFF383843),
            borderStrong = Color(0xFFFFFFFF),
            errorText = Color(0xFFF42500),
            destructive = Color(0xFFF42500),
            statusWarning = Color(0xFFFF234B),
            surfaceMuted = Color(0xFF2C2D34),
            iconSubtle = Color(0xFF98A2B3),
            callReady = Color(0xFF66BB6A),
            callIncoming = Color(0xFFF59E0B),
            callMuted = Color(0xFFF42500),
            callOffline = Color(0xFF98A2B3),
            callWrapUp = Color(0xFF8B5CF6),
        )
    }
}

val LocalYallaColors = staticCompositionLocalOf { YallaColors.Light }
```

- [ ] **Step 4: Apply renames across entire codebase**

Use find-and-replace across all `.kt` files in `src/main/kotlin/`:

| Old Reference | New Reference |
|---------------|---------------|
| `colors.buttonActive` | `colors.brandPrimary` |
| `colors.buttonDisabled` | `colors.surfaceMuted` |
| `colors.iconDisabled` | `colors.brandLight` |
| `colors.iconRed` | `colors.destructive` |
| `colors.pinkSun` | `colors.statusWarning` |
| `colors.borderDisabled` | `colors.borderDefault` |
| `dropdownColors.buttonActive` | `dropdownColors.brandPrimary` |
| `dropdownColors.pinkSun` | `dropdownColors.statusWarning` |
| `dropdownColors.backgroundTertiary` | `dropdownColors.backgroundTertiary` |

Also remove all references to `errorIndicator` (replace with `errorText`).

Also update `AgentStatusButton.kt` local functions that duplicate the color logic:
```kotlin
// In AgentStatusButton.kt, inside the composable:
fun dotColor(status: DisplayAgentStatus): Color = when (status) {
    DisplayAgentStatus.ONLINE -> colors.brandPrimary    // was buttonActive
    DisplayAgentStatus.BUSY -> colors.statusWarning     // was pinkSun
    DisplayAgentStatus.OFFLINE -> colors.textSubtle
}
```

And in the dropdown section:
```kotlin
val statusDotColor = when (status) {
    DisplayAgentStatus.ONLINE -> dropdownColors.brandPrimary
    DisplayAgentStatus.BUSY -> dropdownColors.statusWarning
    DisplayAgentStatus.OFFLINE -> dropdownColors.textSubtle
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.ui.theme.YallaColorsTest" --info`

Expected: PASS — all 3 tests green.

- [ ] **Step 6: Run full test suite**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL tests pass. If any test references old field names (`buttonActive`, `iconRed`, etc.), update those test files too.

- [ ] **Step 7: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/ui/theme/YallaColors.kt
git add src/test/kotlin/uz/yalla/sipphone/ui/theme/YallaColorsTest.kt
git add -u  # pick up all renames across codebase
git commit -m "refactor(ui): rewrite YallaColors with semantic names + fix dark theme

- Remove buttonActive (duplicate of brandPrimary)
- Rename: iconRed→destructive, pinkSun→statusWarning, borderDisabled→borderDefault
- Rename: buttonDisabled→surfaceMuted, iconDisabled→brandLight
- Remove errorIndicator (duplicate of errorText)
- Fix dark backgroundTertiary to be lighter than backgroundSecondary"
```

---

## Task 2: Extend AppTokens — Alpha, Typography, Component Sizes

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/ui/theme/AppTokens.kt`
- Create: `src/test/kotlin/uz/yalla/sipphone/ui/theme/AppTokensTest.kt`
- Update: `src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/uz/yalla/sipphone/ui/theme/AppTokensTest.kt`:

```kotlin
package uz.yalla.sipphone.ui.theme

import kotlin.test.Test
import kotlin.test.assertTrue

class AppTokensTest {

    private val tokens = AppTokens()

    @Test
    fun `alpha tokens are in ascending order`() {
        assertTrue(tokens.alphaSubtle < tokens.alphaMuted)
        assertTrue(tokens.alphaMuted < tokens.alphaLight)
        assertTrue(tokens.alphaLight < tokens.alphaBorder)
        assertTrue(tokens.alphaBorder < tokens.alphaMedium)
        assertTrue(tokens.alphaMedium < tokens.alphaFocus)
    }

    @Test
    fun `all alpha tokens are between 0 and 1`() {
        val alphas = listOf(
            tokens.alphaSubtle, tokens.alphaMuted, tokens.alphaLight,
            tokens.alphaBorder, tokens.alphaMedium, tokens.alphaFocus,
        )
        alphas.forEach { alpha ->
            assertTrue(alpha in 0f..1f, "Alpha $alpha out of range")
        }
    }

    @Test
    fun `typography sizes are in ascending order`() {
        assertTrue(tokens.textXs < tokens.textSm)
        assertTrue(tokens.textSm < tokens.textBase)
        assertTrue(tokens.textBase < tokens.textMd)
        assertTrue(tokens.textMd < tokens.textLg)
        assertTrue(tokens.textLg < tokens.textXl)
        assertTrue(tokens.textXl < tokens.textTitle)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "uz.yalla.sipphone.ui.theme.AppTokensTest" --info`

Expected: FAIL — `alphaSubtle`, `textXs`, etc. don't exist yet.

- [ ] **Step 3: Rewrite AppTokens.kt**

Replace the entire file `src/main/kotlin/uz/yalla/sipphone/ui/theme/AppTokens.kt`:

```kotlin
package uz.yalla.sipphone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AppTokens(
    // ── Spacing ──
    val spacingXs: Dp = 4.dp,
    val spacingSm: Dp = 8.dp,
    val spacingMdSm: Dp = 12.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val spacingXl: Dp = 32.dp,

    // ── Elevation ──
    val elevationNone: Dp = 0.dp,
    val elevationLow: Dp = 2.dp,
    val elevationMedium: Dp = 6.dp,

    // ── Corner Radius ──
    val cornerXs: Dp = 6.dp,
    val cornerSmall: Dp = 8.dp,
    val cornerMedium: Dp = 10.dp,
    val cornerLarge: Dp = 14.dp,
    val cornerXl: Dp = 16.dp,

    // ── Shapes ──
    val shapeXs: Shape = RoundedCornerShape(cornerXs),
    val shapeSmall: Shape = RoundedCornerShape(cornerSmall),
    val shapeMedium: Shape = RoundedCornerShape(cornerMedium),
    val shapeLarge: Shape = RoundedCornerShape(cornerLarge),
    val shapeXl: Shape = RoundedCornerShape(cornerXl),

    // ── Alpha ──
    val alphaSubtle: Float = 0.1f,
    val alphaMuted: Float = 0.12f,
    val alphaLight: Float = 0.15f,
    val alphaBorder: Float = 0.25f,
    val alphaMedium: Float = 0.3f,
    val alphaFocus: Float = 0.5f,

    // ── Typography Sizes ──
    val textXs: TextUnit = 10.sp,
    val textSm: TextUnit = 11.sp,
    val textBase: TextUnit = 12.sp,
    val textMd: TextUnit = 13.sp,
    val textLg: TextUnit = 14.sp,
    val textXl: TextUnit = 16.sp,
    val textTitle: TextUnit = 20.sp,

    // ── Window ──
    val windowMinWidth: Dp = 380.dp,
    val windowMinHeight: Dp = 180.dp,

    // ── Icons ──
    val iconSmall: Dp = 16.dp,
    val iconDefault: Dp = 18.dp,
    val iconMedium: Dp = 24.dp,

    // ── Indicators ──
    val indicatorDot: Dp = 8.dp,
    val indicatorDotLarge: Dp = 10.dp,
    val dividerThickness: Dp = 1.dp,
    val dividerHeight: Dp = 32.dp,

    // ── Component Sizes ──
    val chipHeight: Dp = 28.dp,
    val iconButtonSize: Dp = 36.dp,
    val iconButtonSizeLarge: Dp = 40.dp,
    val fieldHeight: Dp = 36.dp,
    val fieldHeightLg: Dp = 44.dp,
    val segmentButtonSize: Dp = 32.dp,

    // ── Toolbar ──
    val toolbarHeight: Dp = 52.dp,
    val toolbarPaddingH: Dp = 12.dp,
    val toolbarZoneGap: Dp = 8.dp,

    // ── Dropdown ──
    val dropdownItemMinHeight: Dp = 36.dp,
    val dropdownWidth: Dp = 180.dp,

    // ── Settings Dialog ──
    val settingsDialogWidth: Dp = 340.dp,
    val settingsDialogHeight: Dp = 420.dp,
    val settingsCardWidth: Dp = 320.dp,

    // ── Animation ──
    val animFast: Int = 200,
    val animMedium: Int = 300,
    val animSlow: Int = 350,

    // ── Window sizes ──
    val loginWindowSize: DpSize = DpSize(1280.dp, 720.dp),
    val mainWindowSize: DpSize = DpSize(1280.dp, 720.dp),
) {
    fun minimumAwtDimension(): java.awt.Dimension =
        java.awt.Dimension(windowMinWidth.value.toInt(), windowMinHeight.value.toInt())
}

val LocalAppTokens = staticCompositionLocalOf { AppTokens() }
```

- [ ] **Step 4: Update Theme.kt — remove ExtendedColors, simplify**

Replace the entire file `src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt`:

```kotlin
package uz.yalla.sipphone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.materialkolor.rememberDynamicColorScheme
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.strings.RuStrings
import uz.yalla.sipphone.ui.strings.StringResources
import uz.yalla.sipphone.ui.strings.UzStrings

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
)

@Composable
fun YallaSipPhoneTheme(
    isDark: Boolean = false,
    locale: String = "uz",
    content: @Composable () -> Unit,
) {
    val seedColor = Color(0xFF562DF8)
    val colorScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        isAmoled = false,
    )
    val yallaColors = if (isDark) YallaColors.Dark else YallaColors.Light

    val strings: StringResources = when (locale) {
        "ru" -> RuStrings
        else -> UzStrings
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalAppTokens provides AppTokens(),
            LocalYallaColors provides yallaColors,
            LocalStrings provides strings,
            content = content,
        )
    }
}
```

- [ ] **Step 5: Remove ExtendedColors references from codebase**

Search the codebase for `LocalExtendedColors` and `ExtendedColors`. Remove any imports and usages. If nothing else uses them, they're dead code now (they were only defined in Theme.kt and provided via CompositionLocal).

- [ ] **Step 6: Run tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL pass including new AppTokensTest.

- [ ] **Step 7: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/ui/theme/AppTokens.kt
git add src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt
git add src/test/kotlin/uz/yalla/sipphone/ui/theme/AppTokensTest.kt
git add -u
git commit -m "refactor(ui): extend AppTokens with alpha, typography, component sizes

- Add alpha scale: alphaSubtle→alphaFocus (0.1–0.5)
- Add typography scale: textXs→textTitle (10sp–20sp)
- Add component sizes: chipHeight, iconButtonSize, fieldHeight, etc.
- Add cornerXs (6dp) and cornerMedium (10dp)
- Remove ExtendedColors (unused)
- Simplify Theme.kt"
```

---

## Task 3: Create YallaIconButton Component

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/component/YallaIconButton.kt`

This component extracts the repeated icon button pattern from CallActions (8 nearly identical usages).

- [ ] **Step 1: Create YallaIconButton.kt**

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import uz.yalla.sipphone.ui.theme.LocalAppTokens

/**
 * Standard icon button used throughout the toolbar.
 *
 * Consistent sizing (36dp default), pointer cursor, and no minimum
 * interactive component size override.
 */
@Composable
fun YallaIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
    disabledContainerColor: Color = Color.Transparent,
    disabledContentColor: Color = Color.Unspecified,
) {
    val tokens = LocalAppTokens.current

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .size(tokens.iconButtonSize)
                .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = disabledContainerColor,
                disabledContentColor = disabledContentColor,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(tokens.iconDefault),
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/ui/component/YallaIconButton.kt
git commit -m "feat(ui): add YallaIconButton reusable component

Standard icon button with consistent 36dp size, pointer cursor,
and no minimum interactive component size."
```

---

## Task 4: Create YallaTooltip Component

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/component/YallaTooltip.kt`

Fixes the CursorPoint issue — tooltip will be anchored to the component, not follow the mouse.

- [ ] **Step 1: Create YallaTooltip.kt**

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Tooltip anchored to the component (not following the cursor).
 *
 * Appears above the component, centered horizontally.
 * Uses theme colors for background, border, and padding.
 *
 * @param tooltip Content to display inside the tooltip card.
 * @param content The composable that triggers the tooltip on hover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YallaTooltip(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = 400,
    content: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    TooltipArea(
        tooltip = {
            Column(
                modifier = Modifier
                    .background(colors.backgroundSecondary, tokens.shapeSmall)
                    .border(tokens.dividerThickness, colors.borderDefault, tokens.shapeSmall)
                    .padding(horizontal = tokens.spacingMdSm, vertical = tokens.spacingSm),
            ) {
                tooltip()
            }
        },
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.TopCenter,
            alignment = Alignment.TopCenter,
            offset = DpOffset(0.dp, (-8).dp),
        ),
        delayMillis = delayMillis,
        modifier = modifier,
        content = content,
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/ui/component/YallaTooltip.kt
git commit -m "feat(ui): add YallaTooltip with component-anchored placement

Tooltip appears above the component instead of following
the cursor. Uses theme tokens for styling."
```

---

## Task 5: Create YallaDropdownWindow Component

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/component/YallaDropdownWindow.kt`

Fixes the MouseInfo hack — dropdown will be positioned relative to the trigger button using `onGloballyPositioned` + window coordinates.

- [ ] **Step 1: Create YallaDropdownWindow.kt**

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme
import java.awt.event.WindowFocusListener
import java.awt.event.WindowEvent

/**
 * OS-level dropdown window anchored to a screen position.
 *
 * Uses [DialogWindow] (required for rendering above JCEF).
 * Dismisses on focus loss. Caller provides anchor coordinates
 * (screen-space dp) obtained from [rememberAnchorPosition].
 *
 * @param visible Whether the dropdown is shown.
 * @param anchorScreenX Screen X position in dp (from [rememberAnchorPosition]).
 * @param anchorScreenY Screen Y position in dp.
 * @param offset Additional offset from anchor position.
 * @param isDarkTheme Required for re-applying theme inside the OS window.
 * @param locale Required for re-applying strings inside the OS window.
 * @param width Dropdown width.
 * @param height Dropdown height.
 * @param onDismiss Called when the dropdown should close.
 * @param content Dropdown content.
 */
@Composable
fun YallaDropdownWindow(
    visible: Boolean,
    anchorScreenX: Dp,
    anchorScreenY: Dp,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    isDarkTheme: Boolean,
    locale: String,
    width: Dp = LocalAppTokens.current.dropdownWidth,
    height: Dp = 130.dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!visible) return

    val posX = anchorScreenX + offset.x
    val posY = anchorScreenY + offset.y

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "",
        state = rememberDialogState(
            position = WindowPosition(posX, posY),
            size = DpSize(width, height),
        ),
        resizable = false,
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
    ) {
        // Dismiss on focus loss — with proper cleanup
        DisposableEffect(Unit) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {}
                override fun windowLostFocus(e: WindowEvent?) { onDismiss() }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
            val colors = LocalYallaColors.current
            val tokens = LocalAppTokens.current

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(width)
                        .clip(tokens.shapeMedium)
                        .background(colors.backgroundSecondary)
                        .then(Modifier),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Helper to capture the screen position of a composable for anchoring a [YallaDropdownWindow].
 *
 * Usage:
 * ```
 * var anchorX by remember { mutableStateOf(0.dp) }
 * var anchorY by remember { mutableStateOf(0.dp) }
 *
 * Box(
 *     modifier = Modifier.onGloballyPositioned { coords ->
 *         val windowPos = coords.positionInWindow()
 *         val density = ... // LocalDensity.current
 *         // Convert to screen dp by adding window screen position
 *     }
 * )
 * ```
 *
 * Since we're inside a Compose Desktop Window, we can access `window` (AWT Window)
 * to get the window's screen position, then add the component's position within the window.
 */
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/ui/component/YallaDropdownWindow.kt
git commit -m "feat(ui): add YallaDropdownWindow with anchored positioning

OS-level DialogWindow dropdown with:
- Anchored positioning (not mouse-based)
- DisposableEffect for focus listener cleanup
- Theme re-application inside OS window"
```

---

## Task 6: Create YallaSegmentedControl Component

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/component/YallaSegmentedControl.kt`

Replaces the ad-hoc SegmentButton in SettingsDialog with a proper reusable component.

- [ ] **Step 1: Create YallaSegmentedControl.kt**

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Two-option segmented control with theme-aware styling.
 *
 * Each segment is a 32dp box. Selected segment gets a light brand tint background.
 * Wraps in a secondary-colored container with rounded corners and 2dp padding.
 *
 * @param options Pair of composable content for left and right options.
 * @param selectedIndex 0 for left, 1 for right.
 * @param onSelect Called with the selected index (0 or 1).
 */
@Composable
fun YallaSegmentedControl(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    Row(
        modifier = modifier
            .background(colors.backgroundSecondary, tokens.shapeSmall)
            .padding(tokens.spacingXs / 2), // 2dp
        horizontalArrangement = Arrangement.spacedBy(tokens.spacingXs / 2),
    ) {
        SegmentItem(selected = selectedIndex == 0, onClick = { onSelect(0) }) { first() }
        SegmentItem(selected = selectedIndex == 1, onClick = { onSelect(1) }) { second() }
    }
}

@Composable
private fun SegmentItem(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    Box(
        modifier = Modifier
            .size(tokens.segmentButtonSize)
            .clip(tokens.shapeXs)
            .then(
                if (selected) {
                    Modifier.background(colors.brandPrimary.copy(alpha = tokens.alphaLight))
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/ui/component/YallaSegmentedControl.kt
git commit -m "feat(ui): add YallaSegmentedControl component

Reusable two-option segmented toggle with brand tint
for selected state. Uses theme tokens throughout."
```

---

## Task 7: Rewrite CallActions with YallaIconButton

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/CallActions.kt`

Eliminates the 8x copy-pasted IconButton pattern. Uses `YallaIconButton` and new tokens.

- [ ] **Step 1: Rewrite CallActions.kt**

```kotlin
package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.component.YallaIconButton
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Call action buttons — state-dependent row of icon buttons.
 *
 * States: Idle → Ringing (in/out) → Active → Ending.
 */
@Composable
fun CallActions(
    callState: CallState,
    phoneInputEmpty: Boolean,
    onCall: () -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.toolbarZoneGap),
    ) {
        when (callState) {
            is CallState.Idle -> {
                YallaIconButton(
                    icon = Icons.Filled.Call,
                    contentDescription = strings.buttonCall,
                    onClick = onCall,
                    enabled = !phoneInputEmpty,
                    containerColor = colors.brandPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = colors.surfaceMuted,
                    disabledContentColor = colors.brandLight,
                )
                YallaIconButton(
                    icon = Icons.Filled.Mic,
                    contentDescription = strings.buttonMute,
                    onClick = {},
                    enabled = false,
                    disabledContainerColor = colors.surfaceMuted,
                    disabledContentColor = colors.brandLight,
                )
                YallaIconButton(
                    icon = Icons.Filled.Pause,
                    contentDescription = strings.buttonHold,
                    onClick = {},
                    enabled = false,
                    disabledContainerColor = colors.surfaceMuted,
                    disabledContentColor = colors.brandLight,
                )
            }

            is CallState.Ringing -> {
                if (!callState.isOutbound) {
                    YallaIconButton(
                        icon = Icons.Filled.Phone,
                        contentDescription = strings.buttonAnswer,
                        onClick = onAnswer,
                        containerColor = colors.brandPrimary,
                        contentColor = Color.White,
                    )
                    YallaIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = strings.buttonReject,
                        onClick = onReject,
                        containerColor = colors.destructive,
                        contentColor = Color.White,
                    )
                } else {
                    YallaIconButton(
                        icon = Icons.Filled.CallEnd,
                        contentDescription = strings.buttonHangup,
                        onClick = onHangup,
                        containerColor = colors.destructive,
                        contentColor = Color.White,
                    )
                }
                RingingLabel()
            }

            is CallState.Active -> {
                YallaIconButton(
                    icon = Icons.Filled.CallEnd,
                    contentDescription = strings.buttonHangup,
                    onClick = onHangup,
                    containerColor = colors.destructive,
                    contentColor = Color.White,
                )
                YallaIconButton(
                    icon = if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (callState.isMuted) strings.buttonUnmute else strings.buttonMute,
                    onClick = onToggleMute,
                    containerColor = if (callState.isMuted) {
                        colors.brandPrimary.copy(alpha = tokens.alphaLight)
                    } else {
                        colors.backgroundSecondary
                    },
                    contentColor = if (callState.isMuted) colors.brandPrimary else colors.iconSubtle,
                )
                YallaIconButton(
                    icon = if (callState.isOnHold) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (callState.isOnHold) strings.buttonResume else strings.buttonHold,
                    onClick = onToggleHold,
                    containerColor = if (callState.isOnHold) {
                        colors.brandPrimary.copy(alpha = tokens.alphaLight)
                    } else {
                        colors.backgroundSecondary
                    },
                    contentColor = if (callState.isOnHold) colors.brandPrimary else colors.iconSubtle,
                )
            }

            is CallState.Ending -> { /* No controls during ending transition */ }
        }
    }
}

@Composable
private fun RingingLabel() {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    Text(
        text = strings.sipRinging,
        fontSize = tokens.textBase,
        color = colors.brandLight,
        modifier = Modifier
            .background(
                colors.brandPrimary.copy(alpha = tokens.alphaLight),
                tokens.shapeXs,
            )
            .padding(horizontal = tokens.spacingSm, vertical = tokens.spacingXs),
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL pass.

- [ ] **Step 4: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/CallActions.kt
git commit -m "refactor(ui): rewrite CallActions with YallaIconButton

Extract RingingLabel. Replace 8 copy-pasted IconButton instances
with YallaIconButton. Use token-based alpha and sizing."
```

---

## Task 8: Rewrite SipChipRow + CallTimer

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/SipChipRow.kt`
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/CallTimer.kt`

Uses YallaTooltip (component-anchored, not cursor-following) and new tokens.

- [ ] **Step 1: Rewrite SipChipRow.kt**

```kotlin
package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.SipAccount
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.ui.component.YallaTooltip
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun SipChipRow(
    accounts: List<SipAccount>,
    activeCallAccountId: String?,
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacingXs + 2.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        accounts.forEach { account ->
            SipChip(
                account = account,
                isActiveCall = activeCallAccountId == account.id,
                isMutedByCall = activeCallAccountId != null && activeCallAccountId != account.id,
                onClick = { onChipClick(account.id) },
            )
        }
    }
}

@Composable
private fun SipChip(
    account: SipAccount,
    isActiveCall: Boolean,
    isMutedByCall: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    val isClickable = account.state !is SipAccountState.Reconnecting

    val chipStyle = resolveChipStyle(colors, tokens, account.state, isActiveCall, isMutedByCall)

    val statusText = when (account.state) {
        is SipAccountState.Connected -> strings.sipConnected
        is SipAccountState.Reconnecting -> strings.sipReconnecting
        is SipAccountState.Disconnected -> strings.sipDisconnected
    }
    val statusColor = when (account.state) {
        is SipAccountState.Connected -> colors.brandPrimary
        is SipAccountState.Reconnecting -> colors.statusWarning
        is SipAccountState.Disconnected -> colors.destructive
    }

    YallaTooltip(
        tooltip = {
            Text(
                text = account.name,
                fontSize = tokens.textMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.textBase,
            )
            Text(
                text = account.credentials.username,
                fontSize = tokens.textSm,
                color = colors.textSubtle,
            )
            Text(
                text = "${account.credentials.server}:${account.credentials.port}",
                fontSize = tokens.textSm,
                color = colors.textSubtle,
            )
            if (account.credentials.transport != "UDP") {
                Text(
                    text = account.credentials.transport,
                    fontSize = tokens.textSm,
                    color = colors.textSubtle,
                )
            }
            Text(
                text = statusText,
                fontSize = tokens.textSm,
                fontWeight = FontWeight.Medium,
                color = statusColor,
            )
            if (account.state is SipAccountState.Disconnected) {
                Text(
                    text = strings.sipReconnectHint,
                    fontSize = tokens.textXs,
                    color = colors.destructive,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .height(tokens.chipHeight)
                .clip(tokens.shapeXs)
                .background(chipStyle.bgColor, tokens.shapeXs)
                .border(tokens.dividerThickness, chipStyle.borderColor, tokens.shapeXs)
                .then(
                    if (isClickable) {
                        Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = tokens.spacingMdSm - 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val prefix = if (isActiveCall) "\u25CF " else ""
            Text(
                text = "$prefix${account.name}",
                fontSize = tokens.textBase,
                fontWeight = FontWeight.Medium,
                color = chipStyle.textColor,
                maxLines = 1,
            )
        }
    }
}

private data class ChipStyle(
    val bgColor: Color,
    val borderColor: Color,
    val textColor: Color,
)

private fun resolveChipStyle(
    colors: uz.yalla.sipphone.ui.theme.YallaColors,
    tokens: uz.yalla.sipphone.ui.theme.AppTokens,
    state: SipAccountState,
    isActiveCall: Boolean,
    isMutedByCall: Boolean,
): ChipStyle = when {
    isActiveCall -> ChipStyle(
        bgColor = colors.brandPrimary,
        borderColor = colors.brandPrimary,
        textColor = Color.White,
    )
    isMutedByCall && state is SipAccountState.Connected -> ChipStyle(
        bgColor = colors.surfaceMuted,
        borderColor = colors.borderDefault,
        textColor = colors.textSubtle,
    )
    state is SipAccountState.Connected -> ChipStyle(
        bgColor = colors.brandPrimary.copy(alpha = tokens.alphaMuted),
        borderColor = colors.brandPrimary.copy(alpha = tokens.alphaMedium),
        textColor = colors.brandLight,
    )
    state is SipAccountState.Reconnecting -> ChipStyle(
        bgColor = colors.statusWarning.copy(alpha = tokens.alphaSubtle),
        borderColor = colors.statusWarning.copy(alpha = tokens.alphaBorder),
        textColor = colors.statusWarning,
    )
    else -> ChipStyle(
        bgColor = colors.destructive.copy(alpha = tokens.alphaSubtle),
        borderColor = colors.destructive.copy(alpha = tokens.alphaBorder),
        textColor = colors.destructive,
    )
}
```

- [ ] **Step 2: Rewrite CallTimer.kt**

```kotlin
package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun CallTimer(
    duration: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    AnimatedVisibility(
        visible = duration != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        duration?.let { text ->
            Text(
                text = text,
                fontSize = tokens.textBase,
                fontFamily = FontFamily.Monospace,
                color = colors.brandLight,
                modifier = Modifier
                    .background(colors.brandPrimary.copy(alpha = tokens.alphaLight), tokens.shapeXs)
                    .border(
                        tokens.dividerThickness,
                        colors.brandPrimary.copy(alpha = tokens.alphaMedium),
                        tokens.shapeXs,
                    )
                    .padding(horizontal = tokens.spacingSm, vertical = tokens.spacingXs),
            )
        }
    }
}
```

- [ ] **Step 3: Verify and test**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL pass.

- [ ] **Step 4: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/SipChipRow.kt
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/CallTimer.kt
git commit -m "refactor(ui): rewrite SipChipRow and CallTimer

- SipChip tooltip now anchored to component (not cursor)
- Extract ChipStyle data class for color resolution
- Use token-based alpha, typography, and sizing throughout
- CallTimer uses tokens instead of hardcoded values"
```

---

## Task 9: Rewrite AgentStatusButton with Anchored Positioning

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/AgentStatusButton.kt`

Eliminates MouseInfo hack. Uses button's own position + window position for dropdown placement.

- [ ] **Step 1: Rewrite AgentStatusButton.kt**

```kotlin
package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.LocalWindowInfo
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

enum class DisplayAgentStatus {
    ONLINE,
    BUSY,
    OFFLINE,
}

fun AgentStatus.toDisplayStatus(): DisplayAgentStatus = when (this) {
    AgentStatus.READY -> DisplayAgentStatus.ONLINE
    AgentStatus.AWAY, AgentStatus.BREAK, AgentStatus.WRAP_UP -> DisplayAgentStatus.BUSY
    AgentStatus.OFFLINE -> DisplayAgentStatus.OFFLINE
}

fun DisplayAgentStatus.toAgentStatus(): AgentStatus = when (this) {
    DisplayAgentStatus.ONLINE -> AgentStatus.READY
    DisplayAgentStatus.BUSY -> AgentStatus.AWAY
    DisplayAgentStatus.OFFLINE -> AgentStatus.OFFLINE
}

@Composable
fun AgentStatusButton(
    currentStatus: AgentStatus,
    isDarkTheme: Boolean,
    locale: String,
    onStatusSelected: (AgentStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current
    val density = LocalDensity.current

    var showDropdown by remember { mutableStateOf(false) }
    val displayStatus = currentStatus.toDisplayStatus()

    // Track button position in window for anchoring the dropdown
    var buttonWindowX by remember { mutableStateOf(0f) }
    var buttonWindowY by remember { mutableStateOf(0f) }
    var buttonHeight by remember { mutableStateOf(0f) }

    fun dotColor(status: DisplayAgentStatus): Color = when (status) {
        DisplayAgentStatus.ONLINE -> colors.brandPrimary
        DisplayAgentStatus.BUSY -> colors.statusWarning
        DisplayAgentStatus.OFFLINE -> colors.textSubtle
    }

    fun label(status: DisplayAgentStatus): String = when (status) {
        DisplayAgentStatus.ONLINE -> strings.agentStatusOnline
        DisplayAgentStatus.BUSY -> strings.agentStatusBusy
        DisplayAgentStatus.OFFLINE -> strings.agentStatusOffline
    }

    // Button
    Box(
        modifier = modifier
            .size(tokens.iconButtonSize)
            .clip(tokens.shapeSmall)
            .background(colors.backgroundSecondary)
            .pointerHoverIcon(PointerIcon.Hand)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                buttonWindowX = pos.x
                buttonWindowY = pos.y
                buttonHeight = coords.size.height.toFloat()
            }
            .clickable { showDropdown = true },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(tokens.indicatorDotLarge)
                .clip(CircleShape)
                .background(dotColor(displayStatus)),
        )
    }

    // Dropdown
    if (showDropdown) {
        val dropdownWidth = tokens.dropdownWidth
        val dropdownHeight = 130.dp

        // Convert button position to screen dp, then offset below the button
        val screenXDp: Dp
        val screenYDp: Dp
        with(density) {
            // Get the parent window's screen position via AWT
            val awtWindow = java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .activeWindow
            val windowScreenX = awtWindow?.locationOnScreen?.x ?: 0
            val windowScreenY = awtWindow?.locationOnScreen?.y ?: 0

            screenXDp = ((windowScreenX + buttonWindowX) / density.density).dp
            screenYDp = ((windowScreenY + buttonWindowY + buttonHeight) / density.density).dp
        }

        DialogWindow(
            onCloseRequest = { showDropdown = false },
            title = "",
            state = rememberDialogState(
                position = WindowPosition(screenXDp, screenYDp + 4.dp),
                size = DpSize(dropdownWidth, dropdownHeight),
            ),
            resizable = false,
            alwaysOnTop = true,
            undecorated = true,
            transparent = true,
        ) {
            DisposableEffect(Unit) {
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) {}
                    override fun windowLostFocus(e: WindowEvent?) { showDropdown = false }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }

            YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
                val dropdownColors = LocalYallaColors.current
                val dropdownStrings = LocalStrings.current
                val dropdownTokens = LocalAppTokens.current

                Box(modifier = Modifier.size(dropdownWidth, dropdownHeight)) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .size(dropdownWidth, dropdownHeight)
                            .clip(dropdownTokens.shapeMedium)
                            .background(dropdownColors.backgroundSecondary)
                            .padding(dropdownTokens.spacingXs),
                    ) {
                        DisplayAgentStatus.entries.forEach { status ->
                            val isSelected = status == displayStatus
                            val statusDotColor = when (status) {
                                DisplayAgentStatus.ONLINE -> dropdownColors.brandPrimary
                                DisplayAgentStatus.BUSY -> dropdownColors.statusWarning
                                DisplayAgentStatus.OFFLINE -> dropdownColors.textSubtle
                            }
                            val statusLabel = when (status) {
                                DisplayAgentStatus.ONLINE -> dropdownStrings.agentStatusOnline
                                DisplayAgentStatus.BUSY -> dropdownStrings.agentStatusBusy
                                DisplayAgentStatus.OFFLINE -> dropdownStrings.agentStatusOffline
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(dropdownTokens.shapeSmall)
                                    .then(
                                        if (isSelected) {
                                            Modifier.background(dropdownColors.backgroundTertiary)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable {
                                        onStatusSelected(status.toAgentStatus())
                                        showDropdown = false
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(
                                        horizontal = dropdownTokens.spacingMdSm,
                                        vertical = dropdownTokens.spacingSm,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(dropdownTokens.spacingSm),
                            ) {
                                Box(
                                    Modifier
                                        .size(dropdownTokens.indicatorDot)
                                        .clip(CircleShape)
                                        .background(statusDotColor),
                                )
                                Text(
                                    text = statusLabel,
                                    fontSize = dropdownTokens.textMd,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    color = dropdownColors.textBase,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Text(
                                        text = "\u2713",
                                        fontSize = dropdownTokens.textMd,
                                        color = dropdownColors.brandPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify and test**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL pass.

- [ ] **Step 3: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/AgentStatusButton.kt
git commit -m "refactor(ui): rewrite AgentStatusButton with anchored dropdown

- Replace MouseInfo hack with onGloballyPositioned + window coords
- Use DisposableEffect for focus listener cleanup
- Use tokens for all sizing, spacing, and typography"
```

---

## Task 10: Rewrite SettingsDialog

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/SettingsDialog.kt`

Uses YallaSegmentedControl, scrollable content, tokens throughout. Removes inline SegmentButton.

- [ ] **Step 1: Rewrite SettingsDialog.kt**

```kotlin
package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.component.YallaIconButton
import uz.yalla.sipphone.ui.component.YallaSegmentedControl
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

@Composable
fun SettingsDialog(
    visible: Boolean,
    isDarkTheme: Boolean,
    locale: String,
    agentInfo: AgentInfo?,
    onThemeToggle: () -> Unit,
    onLocaleChange: (String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val tokens = LocalAppTokens.current

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "",
        state = rememberDialogState(
            size = DpSize(tokens.settingsDialogWidth, tokens.settingsDialogHeight),
        ),
        resizable = false,
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
    ) {
        YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
            val colors = LocalYallaColors.current
            val strings = LocalStrings.current
            val t = LocalAppTokens.current

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(t.settingsCardWidth)
                        .clip(t.shapeLarge)
                        .background(colors.backgroundTertiary)
                        .border(t.dividerThickness, colors.borderDefault, t.shapeLarge)
                        .padding(t.spacingLg - 4.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = strings.settingsTitle,
                            fontSize = t.textXl,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textBase,
                        )
                        YallaIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = null,
                            onClick = onDismiss,
                            containerColor = colors.backgroundSecondary,
                            contentColor = colors.iconSubtle,
                        )
                    }

                    Spacer(Modifier.height(t.spacingMd))

                    // Agent info card
                    if (agentInfo != null) {
                        AgentInfoCard(agentInfo)
                        Spacer(Modifier.height(t.spacingMd))
                    }

                    // Theme toggle
                    SettingsRow(label = strings.settingsTheme) {
                        YallaSegmentedControl(
                            selectedIndex = if (isDarkTheme) 1 else 0,
                            onSelect = { index ->
                                val wantDark = index == 1
                                if (wantDark != isDarkTheme) onThemeToggle()
                            },
                            first = {
                                Icon(
                                    Icons.Filled.LightMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(t.iconSmall),
                                    tint = if (!isDarkTheme) colors.brandPrimary else colors.textSubtle,
                                )
                            },
                            second = {
                                Icon(
                                    Icons.Filled.DarkMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(t.iconSmall),
                                    tint = if (isDarkTheme) colors.brandPrimary else colors.textSubtle,
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(t.spacingMdSm))

                    // Locale toggle
                    SettingsRow(label = strings.settingsLocale) {
                        YallaSegmentedControl(
                            selectedIndex = if (locale == "ru") 1 else 0,
                            onSelect = { index ->
                                onLocaleChange(if (index == 0) "uz" else "ru")
                            },
                            first = {
                                Text(
                                    text = "UZ",
                                    fontSize = t.textBase,
                                    fontWeight = FontWeight.Medium,
                                    color = if (locale == "uz") colors.brandPrimary else colors.textSubtle,
                                )
                            },
                            second = {
                                Text(
                                    text = "RU",
                                    fontSize = t.textBase,
                                    fontWeight = FontWeight.Medium,
                                    color = if (locale == "ru") colors.brandPrimary else colors.textSubtle,
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(t.spacingLg))

                    // Logout button
                    Button(
                        onClick = {
                            onDismiss()
                            onLogout()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(t.iconButtonSizeLarge)
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = t.shapeSmall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.destructive.copy(alpha = t.alphaMuted),
                            contentColor = colors.destructive,
                        ),
                    ) {
                        Text(
                            text = strings.settingsLogout,
                            fontSize = t.textMd,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(Modifier.height(t.spacingMdSm))

                    // Version
                    Text(
                        text = "Yalla SIP Phone v${SipConstants.APP_VERSION}",
                        fontSize = t.textSm,
                        color = colors.borderDefault,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentInfoCard(agentInfo: AgentInfo) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    val initials = agentInfo.name
        .split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
        .joinToString("")
        .ifEmpty { "?" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.backgroundSecondary, tokens.shapeMedium)
            .padding(tokens.spacingMdSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(tokens.iconButtonSizeLarge)
                .clip(CircleShape)
                .background(colors.brandPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                fontSize = tokens.textLg,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        Spacer(Modifier.width(tokens.spacingMdSm))

        Column {
            Text(
                text = agentInfo.name,
                fontSize = tokens.textLg,
                fontWeight = FontWeight.Medium,
                color = colors.textBase,
            )
            Text(
                text = "ID: ${agentInfo.id}",
                fontSize = tokens.textBase,
                color = colors.textSubtle,
            )
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = tokens.textMd,
            color = colors.textBase,
        )
        content()
    }
}
```

- [ ] **Step 2: Verify and test**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL pass.

- [ ] **Step 3: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/SettingsDialog.kt
git commit -m "refactor(ui): rewrite SettingsDialog with proper Compose

- Use YallaSegmentedControl (remove inline SegmentButton)
- Extract AgentInfoCard and SettingsRow composables
- Add vertical scroll for overflow protection
- Use tokens throughout for spacing, sizing, typography
- Use YallaIconButton for close button"
```

---

## Task 11: Rewrite PhoneField + ToolbarContent

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/PhoneField.kt`
- Update: `src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarContent.kt`
- Update: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainScreen.kt`

- [ ] **Step 1: Rewrite PhoneField.kt**

```kotlin
package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun PhoneField(
    phoneNumber: String,
    onValueChange: (String) -> Unit,
    callState: CallState,
    focusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    val isRinging = callState is CallState.Ringing
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isRinging -> colors.brandPrimary
        isFocused -> colors.brandPrimary.copy(alpha = tokens.alphaFocus)
        else -> colors.borderDefault
    }

    val textColor = when {
        isRinging -> colors.brandPrimary
        else -> colors.textBase
    }

    val fieldTextStyle = TextStyle(
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = tokens.textMd,
        lineHeight = tokens.fieldHeight.value.sp,
    )

    BasicTextField(
        value = phoneNumber,
        onValueChange = onValueChange,
        textStyle = fieldTextStyle,
        singleLine = true,
        cursorBrush = if (isFocused) SolidColor(colors.brandPrimary) else SolidColor(Color.Transparent),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 160.dp)
                    .height(tokens.fieldHeight)
                    .background(colors.backgroundSecondary, tokens.shapeSmall)
                    .border(tokens.dividerThickness, borderColor, tokens.shapeSmall)
                    .padding(horizontal = tokens.spacingSm),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (phoneNumber.isEmpty()) {
                    Text(
                        text = strings.placeholderPhone,
                        style = fieldTextStyle.copy(color = colors.textSubtle),
                    )
                }
                innerTextField()
            }
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
    )
}

/** Extension to make sp from Dp value work inline. */
private val Float.sp get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
```

- [ ] **Step 2: Update ToolbarContent.kt — use new color names**

The only changes needed in ToolbarContent.kt are updating color references. Replace:
- `colors.backgroundBase` → `colors.backgroundBase` (no change needed, name kept)
- `colors.borderDisabled` → `colors.borderDefault` (in VerticalDivider)

In the `VerticalDivider` composable, change:
```kotlin
// Old
.background(colors.borderDisabled)
// New  
.background(colors.borderDefault)
```

Also update `ToolbarContent` settings icon:
```kotlin
// Old
tint = colors.iconSubtle,
// No change — iconSubtle was kept
```

- [ ] **Step 3: Update MainScreen.kt — use new color names**

No changes needed — `backgroundBase` name was kept.

- [ ] **Step 4: Verify and test**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/PhoneField.kt
git add src/main/kotlin/uz/yalla/sipphone/feature/main/toolbar/ToolbarContent.kt
git commit -m "refactor(ui): rewrite PhoneField, update ToolbarContent

- PhoneField uses tokens for sizing, spacing, typography
- ToolbarContent updated for renamed color fields"
```

---

## Task 12: Rewrite LoginScreen

**Files:**
- Rewrite: `src/main/kotlin/uz/yalla/sipphone/feature/login/LoginScreen.kt`

Removes the forced dark theme hack. Gradient background already provides contrast — card uses theme-aware colors with adjusted opacity instead of force-overriding the theme.

- [ ] **Step 1: Rewrite LoginScreen.kt**

```kotlin
package uz.yalla.sipphone.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Login gradient — brand purple tones.
 * Works visually with both light/dark card overlays.
 */
private val SplashGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF7957FF), Color(0xFF562DF8), Color(0xFF3812CE)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

@Composable
fun LoginScreen(component: LoginComponent) {
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current
    val colors = LocalYallaColors.current
    val loginState by component.loginState.collectAsState()

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    val isLoading = loginState is LoginState.Loading || loginState is LoginState.Authenticated
    val errorState = loginState as? LoginState.Error

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashGradient),
        contentAlignment = Alignment.Center,
    ) {
        // Card — uses dark-friendly hardcoded colors since it sits on a purple gradient.
        // We can't rely on theme colors here because the gradient bg demands white/light text
        // regardless of the user's theme preference.
        val cardBg = Color(0xFF1A1A20).copy(alpha = 0.88f)
        val cardTextPrimary = Color.White
        val cardTextSecondary = Color(0xFF98A2B3)
        val cardBorderDefault = Color(0xFF383843)
        val cardSurfaceMuted = Color(0xFF21222B)
        val cardBrandPrimary = colors.brandPrimary // brand stays consistent
        val cardDestructive = colors.destructive
        val cardStatusWarning = colors.statusWarning

        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(tokens.shapeXl)
                .background(cardBg)
                .padding(horizontal = 40.dp, vertical = tokens.spacingXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(tokens.shapeMedium)
                    .background(cardBrandPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(modifier = Modifier.height(tokens.spacingMd))

            // Title
            Text(
                text = strings.loginTitle,
                style = TextStyle(
                    fontSize = tokens.textTitle,
                    fontWeight = FontWeight.Bold,
                    color = cardTextPrimary,
                ),
            )

            Spacer(modifier = Modifier.height(tokens.spacingSm))

            // Subtitle / error
            Box(
                modifier = Modifier.height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    errorState?.type == LoginErrorType.WRONG_PASSWORD -> Text(
                        text = strings.errorWrongPassword,
                        style = MaterialTheme.typography.bodySmall,
                        color = cardDestructive,
                    )
                    errorState?.type == LoginErrorType.NETWORK -> Text(
                        text = strings.errorNetworkFailed,
                        style = MaterialTheme.typography.bodySmall,
                        color = cardStatusWarning,
                    )
                    else -> Text(
                        text = strings.loginSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = cardTextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(tokens.spacingLg - 4.dp))

            // Password field
            val fieldBorderColor = if (errorState?.type == LoginErrorType.WRONG_PASSWORD) {
                cardDestructive
            } else {
                cardBorderDefault
            }

            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = !isLoading,
                textStyle = TextStyle(
                    color = cardTextPrimary,
                    fontSize = tokens.textLg,
                ),
                cursorBrush = SolidColor(cardBrandPrimary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isLoading && password.isNotEmpty()) component.login(password)
                    },
                ),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tokens.fieldHeightLg)
                            .clip(tokens.shapeMedium)
                            .background(cardSurfaceMuted)
                            .border(tokens.dividerThickness, fieldBorderColor, tokens.shapeMedium)
                            .padding(horizontal = tokens.spacingMdSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = cardTextSecondary,
                            modifier = Modifier.size(tokens.iconDefault),
                        )
                        Spacer(modifier = Modifier.width(tokens.spacingSm))
                        Box(modifier = Modifier.weight(1f)) {
                            if (password.isEmpty()) {
                                Text(
                                    text = strings.loginPasswordPlaceholder,
                                    style = TextStyle(
                                        fontSize = tokens.textLg,
                                        color = cardTextSecondary,
                                    ),
                                )
                            }
                            innerTextField()
                        }
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = null,
                                tint = cardTextSecondary,
                                modifier = Modifier.size(tokens.iconDefault),
                            )
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(tokens.spacingMd))

            // Login button
            val buttonText = when {
                isLoading -> strings.loginConnecting
                errorState != null -> strings.loginRetry
                else -> strings.loginButton
            }

            Button(
                onClick = { component.login(password) },
                enabled = !isLoading && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tokens.fieldHeightLg)
                    .pointerHoverIcon(PointerIcon.Hand),
                shape = tokens.shapeMedium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cardBrandPrimary,
                    disabledContainerColor = cardSurfaceMuted,
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(tokens.iconDefault),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.width(tokens.spacingSm))
                }
                Text(
                    text = buttonText,
                    color = Color.White,
                    fontSize = tokens.textLg,
                )
            }

            Spacer(modifier = Modifier.height(tokens.spacingMdSm))

            // Manual connection link
            TextButton(
                onClick = { showManualDialog = true },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = strings.loginManualConnection,
                    color = cardTextSecondary,
                    fontSize = tokens.textMd,
                )
            }

            // Manual connection dialog
            if (showManualDialog) {
                ManualConnectionDialog(
                    isLoading = isLoading,
                    onConnect = { server, port, username, pwd, dispatcher ->
                        showManualDialog = false
                        component.manualConnect(server, port, username, pwd, dispatcher)
                    },
                    onDismiss = { showManualDialog = false },
                )
            }

            Spacer(modifier = Modifier.height(tokens.spacingSm))

            // Version
            Text(
                text = "v${SipConstants.APP_VERSION}",
                color = cardBorderDefault,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ManualConnectionDialog(
    isLoading: Boolean,
    onConnect: (server: String, port: Int, username: String, password: String, dispatcherUrl: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current
    val colors = LocalYallaColors.current

    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5060") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var dispatcherUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.loginManualConnection) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(tokens.spacingSm),
            ) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(strings.labelServer) },
                    placeholder = {
                        Text(
                            strings.placeholderServer,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSubtle.copy(alpha = tokens.alphaDisabled),
                        )
                    },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(strings.labelPort) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.width(100.dp),
                        shape = tokens.shapeMedium,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(strings.labelUsername) },
                        placeholder = {
                            Text(
                                strings.placeholderUsername,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSubtle.copy(alpha = tokens.alphaDisabled),
                            )
                        },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        shape = tokens.shapeMedium,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.labelPassword) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
                OutlinedTextField(
                    value = dispatcherUrl,
                    onValueChange = { dispatcherUrl = it },
                    label = { Text(strings.placeholderDispatcherUrl) },
                    placeholder = {
                        Text(
                            strings.placeholderDispatcherUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSubtle.copy(alpha = tokens.alphaDisabled),
                        )
                    },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapeMedium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(server, port.toIntOrNull() ?: 5060, username, password, dispatcherUrl) },
                enabled = !isLoading && server.isNotEmpty() && username.isNotEmpty(),
                shape = tokens.shapeMedium,
            ) {
                Text(strings.buttonConnect)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.buttonCancel)
            }
        },
    )
}
```

**Note:** The login screen now uses local color constants for the card interior (since it must always look "dark" on the purple gradient), instead of force-overriding the entire theme with `CompositionLocalProvider`. The `ManualConnectionDialog` still uses theme colors since `AlertDialog` renders in its own Material3 context.

Also need to add `alphaDisabled` to AppTokens (0.6f — already exists in the old AppTokens but not in new one). Add this field:

In `AppTokens.kt`, add after `alphaFocus`:
```kotlin
val alphaDisabled: Float = 0.6f,
```

- [ ] **Step 2: Verify and test**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL pass.

- [ ] **Step 3: Commit**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add src/main/kotlin/uz/yalla/sipphone/feature/login/LoginScreen.kt
git add src/main/kotlin/uz/yalla/sipphone/ui/theme/AppTokens.kt
git commit -m "refactor(ui): rewrite LoginScreen, remove forced dark theme hack

- Replace CompositionLocalProvider override with local card colors
- Use tokens for all sizing, spacing, typography
- Add alphaDisabled to AppTokens"
```

---

## Task 13: Full Build Verification + Cleanup

**Files:**
- All files modified in Tasks 1–12

- [ ] **Step 1: Run full test suite**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`

Expected: ALL tests pass.

- [ ] **Step 2: Run full build (compiles for all targets)**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew build`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Check for any remaining old color references**

Search the entire `src/main/` directory for any leftover old field names:

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
grep -rn "buttonActive\|iconDisabled\|iconRed\|pinkSun\|borderDisabled\|buttonDisabled\|errorIndicator" src/main/kotlin/ || echo "No old references found"
```

Expected: "No old references found". If any remain, fix them.

- [ ] **Step 4: Check for unused imports**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && grep -rn "import.*ExtendedColors\|import.*LocalExtendedColors" src/main/kotlin/ || echo "Clean"`

Expected: "Clean".

- [ ] **Step 5: Visual smoke test**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew run`

Check manually:
1. Login screen renders correctly on purple gradient
2. Main screen toolbar renders with correct colors
3. SIP chips tooltip appears ABOVE the chip (not at cursor)
4. Agent status dropdown appears BELOW the button (not at mouse position)
5. Settings dialog opens centered with proper segmented controls
6. Dark/Light theme toggle works correctly
7. Dark theme backgroundTertiary is visually lighter than backgroundSecondary

- [ ] **Step 6: Final commit if any fixups needed**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
git add -u
git commit -m "fix(ui): cleanup remaining issues from UI layer rewrite"
```

---

## Summary

| Task | What | Files Changed |
|------|------|--------------|
| 1 | YallaColors semantic rename + dark fix | YallaColors.kt + all UI files (rename) |
| 2 | AppTokens alpha/typography/sizing | AppTokens.kt, Theme.kt |
| 3 | YallaIconButton component | ui/component/YallaIconButton.kt |
| 4 | YallaTooltip component | ui/component/YallaTooltip.kt |
| 5 | YallaDropdownWindow component | ui/component/YallaDropdownWindow.kt |
| 6 | YallaSegmentedControl component | ui/component/YallaSegmentedControl.kt |
| 7 | Rewrite CallActions | CallActions.kt |
| 8 | Rewrite SipChipRow + CallTimer | SipChipRow.kt, CallTimer.kt |
| 9 | Rewrite AgentStatusButton | AgentStatusButton.kt |
| 10 | Rewrite SettingsDialog | SettingsDialog.kt |
| 11 | Rewrite PhoneField + ToolbarContent | PhoneField.kt, ToolbarContent.kt |
| 12 | Rewrite LoginScreen | LoginScreen.kt |
| 13 | Full verification + cleanup | All |
