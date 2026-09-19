# Myau+

A Minecraft **1.8.9 Forge** utility client, maintained by **Ziggy**.

---

## Features

Everything from the base client, plus this fork's own work:

**HUD**
- `Hotbar` — custom rounded hotbar with a gradient XP bar
- `ArmorHUD` — armour pieces with durability bars
- `PotionHUD` — active effects with remaining time and a draining bar
- `Keystrokes` — WASD / mouse / space overlay with live CPS (counts AutoClicker's clicks too)
- `HotbarText` — item name above the hotbar, fading in and out
- `HudEditor` — drag every HUD element where you want it (`.hud`), with edge, centre and
  element-to-element snapping

**Render**
- `CylinderESP` — smooth round cylinder ESP with a soft fade, spinning top ring and hurt flash

**Combat**
- `Hitflick` / `VoidFlick` — knockback displacement, with rotations ramped over several ticks,
  GCD-aligned and clamped inside the target's hitbox
- `MoreKB` — sprint reset with a `WTAP` mode that goes through the real input path
- `VelocityPreserver` — holds incoming knockback by delaying the inbound packet stream

**Utility**
- `Updater` — checks GitHub releases, can download in the background and install on exit

---

## Building

```bash
git clone https://github.com/ZiggyLauncher/OpenMyau-Plus.git
cd OpenMyau-Plus
./gradlew build
```

The jar lands in `build/libs/` and contains both the client and its scripting support.

---

## Updating

The `Updater` module checks the releases of this repository.

- **Off** — never checks
- **Notify** — tells you in chat when a newer release exists
- **Auto** — downloads it in the background and installs it when you quit the game

`.update check` and `.update install` do the same by hand. `Repo-Owner` and `Repo-Name` point the
updater at whichever repository you build from.

---

## Scripting

Script support comes from [`rsl/`](rsl/), a vendored copy of
[Raven Script Loader](https://codeberg.org/monster-energy/raven-script-loader) by `@maya.gay`.
It is bundled into the client jar, so one jar registers two Forge mods, `myau` and `rsl`.

---

## Credits and licence

Licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

This is a fork of [OpenMyau-Plus](https://github.com/IamNespola/OpenMyau-Plus) by nespola001,
itself based on OpenMyau. It also bundles [ViaVersion / ViaMCP](https://viaversion.com/) and an
account manager by `ksyz`. Those projects keep their own authorship and licences.
