# RayTraceAntiFreeCam
RayTraceAntiFreeCam prevents illegitimate block interactions on Minecraft servers by validating line-of-sight and reach before allowing players to click, use, or break blocks.

# About
**RayTraceAntiFreeCam** is a lightweight anti-freecam protection plugin that prevents players from interacting with blocks they should not be able to reach or “see” legitimately.

## Important Note
This plugin **does not detect** or prevent the client from moving its camera (freecam movement itself).
Instead, it blocks **illegitimate block interactions** (opening, clicking, breaking, placing, etc.) by validating that the target block is actually interactable from the player’s eye position using a ray-trace / line-of-sight style check.

## Features
- Blocks “through-wall” interactions
  - Prevents opening containers (chests, barrels, shulkers, etc.) through blocks.
  - Prevents using buttons, levers, doors, trapdoors, etc. through walls.
- Prevents illegitimate block breaking
  - If the block is not realistically reachable/visible, breaking is cancelled.
- Prevents illegitimate building/placing via interaction filtering
  - Placement attempts that rely on clicking blocks through obstacles are denied (handled via click validation).
- Reach-aware
  - Uses the player’s real interaction range attribute (when available) and applies configurable limits.
- Optimized for stability
  - Avoids “random” false positives caused by yaw/pitch desync by not relying on the player’s exact view direction.
  - Uses short-term caching to keep decisions consistent while mining/holding clicks.

## Permissions
`raytraceantifreecam.bypass` - Bypasses all checks. Default: OP.

## Config (config.yml)
```
# Maximum allowed interaction distance (hard cap).
# Type: double | Typical: 5.0–6.0
max-reach: 6.0

# Extra tolerance added to reach to reduce edge-case false blocks (corners/close range/desync).
# Type: double | Typical: 0.25–1.0
extra-reach: 0.75

# Cache duration (ms) for the last player+block decision to prevent flickering while mining/holding click.
# Type: long | Typical: 100–250
cache-ttl-ms: 175

check:
  # Validate block interactions (right/left click on blocks: open/use/activate).
  # Type: boolean
  interact: true

  # Validate block breaking (mining).
  # Type: boolean
  break: true
```
