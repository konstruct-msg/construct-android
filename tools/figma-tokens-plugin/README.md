# Konstruct tokens — Figma plugin

A local development plugin for the owner's Figma file «Конструкт». It works on any Figma plan:
it runs in Figma Desktop, not through the MCP connector, so the connector's call limits do not apply.

The file already holds what the code defines, under the code's names:

- variables `CTColor · Dark` / `CTColor · Light` (Starter allows one mode per collection), `CTSpace`,
  `CTIcon`, `CTLayout`, `CTRadius` — each with Android and iOS code syntax;
- text styles `CTFont/…` in JetBrains Mono;
- the **Components** page: `Icon`, `CTButton`, `SectionHeader`, `CTSettingsRow`, `CTNavBar`,
  `ChatNavBar`, `CTSearchBar`, `CTTabBar`, `MessageBubble`, `MessageInput`, `Avatar`, `ChatRow`, `CallRow`.

## Install

Figma Desktop → open the file → **Plugins → Development → Import plugin from manifest…** → pick
this folder's `manifest.json`. It then appears under Plugins → Development → Konstruct tokens.

## Commands

- **Build screens (dark)** — composes ten screens (Chats, Chat, Contact profile, Synaps, Calls,
  Settings, Account, Appearance, Security, Network) from the components, in the section
  "Screens · Dark" on the **Current** page. Each run deletes that section and builds it again, so do
  not edit inside it by hand — edit the components and variables, or copy a screen out first.
- **Export tokens (JSON)** — shows every variable and `CTFont` style as JSON. Copy it into
  `design-tokens.json`: it is the input for updating `ui/theme` (Android) and
  `ConstructTheme.swift` (iOS).

## The round trip

1. A designer changes values: variables (colours, spacing, sizes, radii) and `CTFont` styles.
   Names stay — a name is what the code reads.
2. Export tokens → `design-tokens.json`.
3. The values go into the code in one PR, reviewed as a diff. Every screen already reads tokens,
   so a value changes everywhere at once.

After the first export, the values' source is Figma; the code follows it.
