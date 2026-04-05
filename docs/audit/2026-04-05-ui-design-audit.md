# UI/UX Design Audit — 2026-04-05

## Critical Findings

### P0 — Must fix before production

| # | Issue | Current | Required | File |
|---|-------|---------|----------|------|
| 1 | IconButton sizes | 32dp | 40dp min (M3) / 44dp (HIG) | CallControls.kt |
| 2 | Toolbar icon sizes | 16dp | 20dp | CallControls.kt |
| 3 | Toolbar height | 44dp | 52dp | AppTokens.kt |
| 4 | WCAG-failing colors | callIncoming #F59E0B (2.1:1) | #D97706 (3.5:1+) | YallaColors.kt |
| 5 | Disabled alpha | 0.3f | 0.38f (M3) | CallControls.kt |
| 6 | AgentStatus click targets | ~20dp | 36dp+ | AgentStatusDropdown.kt |

### Key Guidelines
- Apple HIG: 44x44pt minimum clickable area
- Material Design 3: 40dp container, 48dp touch target for IconButtons
- WCAG AA: 4.5:1 contrast for normal text, 3:1 for large text
- M3 Spacing: 4dp grid system
- Desktop hover states: 8% state layer opacity

### Full report from ui-designer agent saved separately.
