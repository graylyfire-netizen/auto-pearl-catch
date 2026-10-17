# auto-pearl-catch

OyVey-ported client build for **Minecraft Java 1.21.11** (Fabric), with the **PearlCatch** module: it throws a wind charge that precisely meets an ender pearl already in flight.

## Download

Grab the ready-to-use jar `oyvey-pearlcatch-1.21.11.jar` from the [Releases page](https://github.com/graylyfire-netizen/auto-pearl-catch/releases), or take the copy at the repo root.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.18.4+ for Minecraft Java 1.21.11
2. Download [Fabric API for 1.21.11](https://modrinth.com/mod/fabric-api) into your `mods` folder
3. Put `oyvey-pearlcatch-1.21.11.jar` in the same `mods` folder and start the game

CatFormat is bundled inside the jar - nothing else needed.

## PearlCatch settings

Range, Window, HitRadius, Pick mode (`CLOSEST`/`EARLIEST`/`BEST_FIT`), AutoLatency, OwnPearls, WindBursts, BurstBoost, BurstRadius, LineOfSight, Cooldown, Inventory, Silent, Debug.

## Source layout

- `src/main/java/.../features/modules/combat/PearlCatchModule.java` - game glue: read state, solve, throw silently
- `src/main/java/.../util/prediction/InterceptSolver.java` - pure-math intercept solver (also a standalone test suite in `src/test/.../InterceptSolverCheck.java`)
- Full oyvey-ported client base (MIT). Rebuild with `./gradlew build` (Java 21), output in `build/libs/`.

## License

MIT.
