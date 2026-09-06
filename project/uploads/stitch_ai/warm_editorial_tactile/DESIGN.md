---
name: Warm Editorial Tactile
colors:
  surface: '#faf9f4'
  surface-dim: '#dbdad5'
  surface-bright: '#faf9f4'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f5f4ef'
  surface-container: '#efeee9'
  surface-container-high: '#e9e8e3'
  surface-container-highest: '#e3e3de'
  on-surface: '#1b1c19'
  on-surface-variant: '#3d4946'
  inverse-surface: '#30312e'
  inverse-on-surface: '#f2f1ec'
  outline: '#6d7a76'
  outline-variant: '#bcc9c5'
  surface-tint: '#006b5f'
  primary: '#006b5f'
  on-primary: '#ffffff'
  primary-container: '#12a594'
  on-primary-container: '#00322c'
  inverse-primary: '#5fdac7'
  secondary: '#8d4f00'
  on-secondary: '#ffffff'
  secondary-container: '#ffa449'
  on-secondary-container: '#6f3d00'
  tertiary: '#516169'
  on-tertiary: '#ffffff'
  tertiary-container: '#85959f'
  on-tertiary-container: '#1f2e35'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#7ef7e3'
  primary-fixed-dim: '#5fdac7'
  on-primary-fixed: '#00201c'
  on-primary-fixed-variant: '#005047'
  secondary-fixed: '#ffdcc0'
  secondary-fixed-dim: '#ffb876'
  on-secondary-fixed: '#2d1600'
  on-secondary-fixed-variant: '#6b3b00'
  tertiary-fixed: '#d5e5ef'
  tertiary-fixed-dim: '#b9c9d3'
  on-tertiary-fixed: '#0e1d25'
  on-tertiary-fixed-variant: '#3a4951'
  background: '#faf9f4'
  on-background: '#1b1c19'
  surface-variant: '#e3e3de'
typography:
  display-lg:
    fontFamily: Noto Serif
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.02em
  display-lg-mobile:
    fontFamily: Noto Serif
    fontSize: 26px
    fontWeight: '600'
    lineHeight: 34px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Noto Serif
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 30px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Noto Serif
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 26px
    letterSpacing: '0'
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 26px
    letterSpacing: -0.005em
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 22px
    letterSpacing: '0'
  body-md-medium:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 22px
    letterSpacing: '0'
  caption:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.01em
  code-timestamp:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.02em
  code-stat:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.04em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  space-xxs: 0.25rem
  space-xs: 0.5rem
  space-sm: 0.75rem
  space-md: 1rem
  space-lg: 1.25rem
  space-xl: 1.5rem
  space-2xl: 2rem
  space-3xl: 3rem
  card-pad-mobile: 1rem
  card-pad-desktop: 1.5rem
  gutter-mobile: 1rem
  gutter-desktop: 1.5rem
---

## Brand & Style
The design system positions the AI recording companion not as an impersonal computing dashboard, but as a living, self-organizing bespoke paper notebook. It evokes the sensory calmness of archival stationery, tactile card stocks, and editorial layout discipline, infused with discreet AI dynamism. 

### Core Ethos
- **Warm Editorial Calm:** Eliminates clinical pure whites (#FFFFFF) and cold blue-grays in favor of warm, mineral paper substrates (#F4F3EE) and rich sumi ink tones.
- **Physical Stationery Metaphors:** Cards feel like individually cut sheets of heavy paper stock, using 3px solid left color rails as structural indexing tabs rather than digital badges.
- **Thoughtful Intelligence:** AI states express themselves through slow, fluid breathing gradients and organic ambient transitions instead of abrupt neon glows or mechanical telemetry rings.

## Colors
The palette balances natural botanicals and archival pigments. Every hue is calibrated against the warm paper substrate to ensure softness, readability, and immediate editorial clarity.

### Color Tokens & Semantic Roles
- **Substrate & Background:**
  - `bg-paper-root`: `#F4F3EE` — Base app canvas, soft warm unbleached paper tone.
  - `bg-paper-card`: `#FAF9F5` — Primary card elevation; crisp, slightly lifted parchment.
  - `bg-paper-elevated`: `#FFFFFF` — Focused sheets, bottom drawers, and active popovers.
  - `bg-paper-sunken`: `#EBE9E1` — Input wells, waveform troughs, and secondary tag containers.

- **Primary Signature (Mint Verdant):**
  - `primary`: `#12A594` — Main interactive voice, brand accents, transcription milestones, verified tags.
  - `primary-container`: `#E3F5F2` — Soft container backgrounds and low-stress highlighting.
  - `primary-dark`: `#0E7D70` — High-contrast press states and focused outline borders.

- **Secondary Signal (Amber Ocher):**
  - `secondary` / `signal-amber`: `#C9781E` — Exclusively reserved for action items, unconfirmed insights, high-priority follow-ups, and risk alerts. Never used for generic decoration.
  - `signal-amber-container`: `#FBF1E4` — Amber card background tint, todo row highlights.
  - `signal-amber-border`: `#E8A355` — Active todo indicators and warning outlines.

- **Ink & Typography Hierarchy:**
  - `ink-primary`: `#1D2423` — Headings, transcript transcripts, actionable titles (deep botanical ink).
  - `ink-secondary`: `#535F5B` — Timestamps, speaker metadata, contextual notes.
  - `ink-muted`: `#8C9793` — Inactive metadata, secondary divider markers, waveform tracks.

- **Editorial Rail Tones (3px Left Border):**
  - `rail-voice`: `#12A594` (Mint) — Direct audio transcripts, speaker segments.
  - `rail-ai`: `#5B7380` (Slate Teal) — AI automatic summaries, mind maps, structured recaps.
  - `rail-action`: `#C9781E` (Amber) — Action items, pending decisions, commitments.
  - `rail-urgent`: `#D04838` (Madder Red) — Blockers, critical deadlines, conflicting facts.

## Typography
The system crafts an intentional friction between scholarly editorial prose and modern functional scanning:
- **Headlines (Noto Serif / 思源宋体):** Imbues recordings with the gravity of book chapters or journalistic excerpts. Used for document titles, AI-generated chapter summaries, and key meeting takeaways.
- **Body & Controls (Plus Jakarta Sans):** Highly legible, humanistic, geometric sans-serif that retains warmth while delivering crisp clarity across long transcription streams.
- **Timestamps & Recording Metrics (JetBrains Mono):** Provides a clean, mechanical counterpoint for audio durations, hardware sync markers, and word counts.

## Layout & Spacing
A responsive fluid column system calibrated around mobile-first hardware companionship with desktop web reading symmetry:
- **Mobile (Base):** Single column fluid container with `16px` outer page gutters. Cards span `calc(100% - 32px)` with internal padding of `16px` to maximize screen real estate while maintaining an uncluttered boundary.
- **Tablet (600px - 1024px):** Split layout. The left pane (340px fixed) handles audio tracks, waveforms, and live hardware control; the right pane is fluid for structured document reading.
- **Desktop (>1024px):** Fixed reading canvas capped at `880px` for optimal typographic line length (55–75 characters), or split three-column workbench (`280px` navigation, `600px` transcription/editorial, `320px` action extraction drawer).
- **Vertical Rhythm:** 4px base increment. Spacing follows dense, unified step ratios: `8px` between related badge tags, `16px` between card paragraphs, and `24px` between distinct temporal session segments.

## Elevation & Depth
Depth is modeled after physical sheets of paper stacked on a wooden desk. No harsh digital drop shadows or heavy blur layers.

- **Stack Level 0 (Desk Root):** Solid `bg-paper-root` (`#F4F3EE`). Completely flat.
- **Stack Level 1 (Default Sheet/Card):** `bg-paper-card` (`#FAF9F5`) wrapped with a subtle 1px border `rgba(43, 58, 66, 0.07)` and an ultra-diffused tactile drop shadow: `0 2px 8px -2px rgba(29, 36, 35, 0.04), 0 1px 2px rgba(29, 36, 35, 0.02)`.
- **Stack Level 2 (Active/Floating Panels):** Recording floating controls, quick-memo drawers: `bg-paper-elevated` (`#FFFFFF`), with an ambient shadow: `0 8px 24px -4px rgba(29, 36, 35, 0.08), 0 2px 6px -1px rgba(29, 36, 35, 0.04)`, enclosed with `rgba(43, 58, 66, 0.09)` border.
- **Stack Level -1 (Recessed Audio Wells):** Waveform playback scrubbers and text-input wells use `bg-paper-sunken` (`#EBE9E1`) with an inner top shadow: `inset 0 1px 2px rgba(29, 36, 35, 0.06)` and no border.

## Shapes
The visual identity relies on generous, soft, non-aggressive card geometry.
- **Cards & Primary Modules:** Standardized to `rounded-2xl` (`16px`). Soft enough to feel friendly and tactile, firm enough to preserve formal typographic margins.
- **Buttons, Pill Filters & Badges:** `rounded-full` (`9999px`) to maintain contrast against the rectangular structural blocks.
- **Inner Recessed Controls (Checkboxes, mini media buttons):** `rounded-md` (`6px` to `8px`) maintaining harmonious internal geometry.

## Components

### 1. Cards with 3px Section Rails (The Anchor Component)
- **Structure:** `rounded-2xl`, background `#FAF9F5`, 1px border `rgba(43, 58, 66, 0.07)`, padded by `16px` (`20px` on desktop).
- **The 3px Rail:** Positioned on the inner left edge using an inset border or absolute 3px strip spanning the full card height without overflowing the `16px` border radius (`overflow-hidden`).
  - *Transcript Card:* 3px `#12A594` left border.
  - *AI Insight / Summary Card:* 3px `#5B7380` left border.
  - *Action / Todo Card:* 3px `#C9781E` left border.
  - *Critical / Blocker Card:* 3px `#D04838` left border.

### 2. Action Items & Amber Checkboxes
- **Pending Action Row:** Background `#FAF9F5`, hover state transitions to `#FBF1E4`.
- **Checkbox:**
  - Base: 18px × 18px, `rounded-md` (5px), 1.5px border `#C9781E`, background transparent.
  - Checked: Solid `#C9781E` fill with a sharp chalk-white `#FAF9F5` checkmark icon.
  - Label: `body-md-medium`. Upon completion, transitions to strikethrough with text color dropping to `ink-muted` (`#8C9793`).

### 3. State Indicator Dots
Mini circular 6px or 8px indicators communicating hardware and sync status:
- **Recording Active:** 8px `#D04838` pulsing dot with concentric fading ripple.
- **Hardware Connected / Standby:** 6px `#12A594` solid calm dot.
- **Syncing / AI Processing:** 6px `#C9781E` with subtle opacity respiration (0.4 to 1.0).
- **Offline / Low Battery:** 6px `#8C9793` outline dot.

### 4. Tactile Waveform Player
- Container sits recessed in `bg-paper-sunken` (`#EBE9E1`) with `rounded-xl` and `8px` vertical padding.
- Waveform bars are 2px wide with 2px gap, rounded ends.
  - **Played Section:** Solid `#12A594` bars.
  - **Remaining Section:** `#BDC4C1` warm gray-green bars.
  - **Action Annotation Marker:** 4px amber teardrop pip (`#C9781E`) positioned along the waveform track marking where actionable tasks were extracted.

### 5. AI Gradient Breathing Orb (AI Assistant Indicator)
- Spherical visual element used during live synthesis and smart summarization.
- **Gradient Blend:** Radial gradient transitioning smoothly from `#12A594` (center) to `#58C2B6` through `#FBF1E4` to `#C9781E` (outer halo).
- **Motion:** 3.2-second gentle scale rhythm (`scale(0.95)` to `scale(1.06)`) paired with an ambient rotating soft blur (`filter: blur(12px)` to `blur(18px)`) under 25% opacity, evoking fluid thought rather than mechanical processing.

### 6. Buttons & Interactive Controls
- **Primary Button:** Mint verdant `#12A594` background, `#FFFFFF` text, `rounded-full`, 40px height, subtle inset top highlight (`inset 0 1px 0 rgba(255,255,255,0.2)`).
- **Action Highlight Button:** Amber ocher `#C9781E` background, `#FFFFFF` text, used exclusively for "Commit to Tasks", "Export Actions", or "Confirm Recording".
- **Secondary Button:** Surface `#FAF9F5`, 1px border `rgba(43, 58, 66, 0.12)`, `#1D2423` text, `rounded-full`.