package com.nikhil.sentinelx.desktop.ui.theme

import androidx.compose.ui.graphics.Color

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  THE ABYSS  —  Backgrounds
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val BackgroundDeep    = Color(0xFF070709)   // Pure void
val BackgroundVoid    = Color(0xFF050507)   // Deepest obsidian
val BackgroundCenter  = Color(0xFF141416)   // Lifted stone center
val SurfaceStone      = Color(0xFF1A1A1E)   // Weathered stone card
val SurfaceGlass      = Color(0xCC1A1A1E)   // Translucent glass
val SurfaceGem        = Color(0xFF222226)   // Elevated gem surface
val SurfaceElevated   = Color(0xFF2A2A30)   // Highest surface

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  SOVEREIGN GOLD  —  The Ancient Power
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val GoldTarnished     = Color(0xFFD4A853)   // Primary warm gold
val GoldBright        = Color(0xFFE8C070)   // Highlight gold
val GoldIce           = Color(0xFFF0DFB0)   // Pale gold text
val GoldDark          = Color(0xFF8A6830)   // Deep aged gold
val GoldDim           = Color(0xFF4A3A18)   // Ultra-dim gold for bg
val GoldShimmer       = Color(0xFFFFE5A0)   // Shimmer highlight

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ETHEREAL CYAN  —  Technology & Magic
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val CyanGlow          = Color(0xFF00E5FF)   // Electric keyhole
val CyanElectric      = Color(0xFF40F0FF)   // Bright electric
val CyanSoft          = Color(0xFF80D8E8)   // Soft teal
val CyanMuted         = Color(0xFF006A7A)   // Muted deep cyan
val CyanGlowDim       = Color(0x1200E5FF)   // Ambient glow bg

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  FINANCIAL SPECTRUM  —  Income / Expense
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val IncomeGreen       = Color(0xFF2ED573)   // Incoming / positive
val IncomeGreenDim    = Color(0xFF0D3B1F)   // Incoming bg tint
val ExpenseRed        = Color(0xFFFF4757)   // Outgoing / negative
val ExpenseRedDim     = Color(0xFF3B0D15)   // Outgoing bg tint

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  TEXT HIERARCHY  —  Parchment Scroll
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val TextParchment     = Color(0xFFEAE0CC)   // Primary body text
val TextSubtle        = Color(0xFFAA9E88)   // Secondary/lore text
val TextMuted         = Color(0xFF665E50)   // Disabled / placeholder

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ALERTS & STATUS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val CrimsonDied       = Color(0xFFB01A2D)   // YOU DIED red
val PurpleMystic      = Color(0xFFBB86FC)   // Mystic purple accent
val AmberWarn         = Color(0xFFE8A830)   // Warning amber

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  GRADIENT PRESETS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val GoldGradient      = listOf(GoldBright, GoldTarnished, GoldDark)
val CyanGradient      = listOf(CyanElectric, CyanGlow, CyanMuted)
val GoldCyanGradient  = listOf(GoldBright, CyanGlow)
val CardBgGradient    = listOf(SurfaceGem, SurfaceStone, BackgroundDeep)
val IncomeGradient    = listOf(IncomeGreen, Color(0xFF1A8A45))
val ExpenseGradient   = listOf(ExpenseRed, Color(0xFF8A1A25))