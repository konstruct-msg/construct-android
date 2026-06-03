# Brand source assets

Original design sources (vector logo + iOS app icons). **Not packaged** into the
APK — kept here for regeneration. The shippable Android assets are generated from
these and live under `app/src/main/res/`.

| Source | Generated Android asset | Notes |
|---|---|---|
| `Konstruct-Logo.pdf` | `res/drawable/ic_konstruct_logo.xml` | Single monochrome path → VectorDrawable, tinted at call site. |
| `Konstruct-Icon-iOS-ClearDark-*.png` | `res/drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml` | Adaptive launcher icon: light glyph on dark gradient. |
| `Konstruct-LogoDark/Light.pdf` | (not needed) | Color variants — Android uses one tintable vector instead. |

## Regenerating the logo vector (PDF → VectorDrawable)

```bash
pdftocairo -svg Konstruct-Logo.pdf logo.svg      # PDF → SVG (poppler)
# then SVG → VectorDrawable: Android Studio “Vector Asset”, or vd-tool,
# or hand-port the <path d="…"> into <path android:pathData="…">.
```

The logo is a single path with no gradients, so the VectorDrawable is one
`<path>`. Keep `fillColor` white and tint where used
(`Icon(painterResource(R.drawable.ic_konstruct_logo), tint = …)`).

## Launcher icon

minSdk is 26, so the adaptive icon (`mipmap-anydpi/ic_launcher.xml`) is always
used; the per-density `.webp` files are legacy fallbacks. For a pixel-perfect
icon matching the iOS gradients, regenerate via Android Studio → Image Asset
Studio using the PNGs above.
