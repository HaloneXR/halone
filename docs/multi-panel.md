# Multi-panel: following, layout and collisions

Everything here was found by wearing it — the unit
tests came second, and in several cases the first version of a test was wrong in
the same way the code was. Twice in the fourth session a test written to pin a
bug passed without the fix, and only a recording from the glasses showed why.

The short version: **following is a property of the arrangement, not of a
panel**, and almost every bug in this area was a wrong *reference frame* rather
than a wrong coefficient.

## The model as it now stands

A `Panel` owns its pose, its slot, its engine and its `SmoothFollow`. A `Scene`
owns the arrangement: which slots exist, whether followers may rest on each
other, and when the arrangement moves at all.

| Behaviour | Where | Value |
|---|---|---|
| Slot spacing around the cylinder | `Scene.layOutCylindrical` | 30° (wants to be a setting) |
| Band before the arrangement follows | `Scene.hasLeftTheBand` | arrangement extent + 6° |
| Extra distance before a drift starts | `Scene.DRIFT_MIN_EXCESS` | 6°, and 0.3 s of rest |
| Return to slot | `SmoothFollow.timeConstantSeconds` | 2.5 s, floor 6°/s |
| Crossing a pinned panel | `SmoothFollow.crossing*` | 0.25 s, floor 150°/s |
| Clearance when parked beside a panel | `Scene.CROSSING_CLEARANCE` | 6° past touching |
| Collision policy | `Scene.collisionPolicy` | `AVOID_ALL` |
| Clearance between two followers | `Scene.FOLLOWER_CLEARANCE` | 2° past touching |
| Elevation a drag may reach | `Scene.MAX_PLACEMENT_PITCH` | 25° |
| Catch-up after a deliberate turn | `Scene.catchUpSeconds` | 0.2 s, floor 150°/s |
| Panel behind anchor, head-triggered | `Scene.TIGHT_TRACKING_SECONDS` | 0.1 s |
| Lift while a panel is held | `ChromeLayout.GRAB_LIFT_METRES` | 0.15 m |

Rules, in the order they matter:

1. **A follower chases its own slot relative to the wearer**, not the wearer's
   heading. Chasing the heading is indistinguishable with one panel and
   catastrophic with two.
2. **The band is measured from the arrangement's outer edges**, so the wearer can
   look anywhere across the panels without anything moving. Derived from the
   panels' angular width, so a wide panel earns a wide band on its own. It is a
   *span*, not a radius: measuring it as the largest `|slot|` assumes the slots
   are spread symmetrically about forward, which one hand-placed panel makes
   false — a slot 80° off to one side made the band 80° wide in both directions
   and the arrangement stopped following at all.
3. **The band decides when to start, and a head-triggered return keeps
   answering the head.** Panels report their own arrival, because a follower
   parked clear of a pinned neighbour is deliberately not at its slot. While a
   return the head started is running, its destination follows the wearer's
   heading — and if they look back well inside the band the arrangement started
   from (3° of hysteresis), it goes back there. Latching the heading instead
   meant a glance away and back sent the panel the whole way over and then the
   whole way back: *"it goes all the way to the left, then back to the right.
   This feels awkward."* The pace and the panel the return centres on are still
   decided once — see "Still unverified" for why. Commanded moves (layout,
   `requestReturn`, overlap repair) keep their fixed destination. Worn: *"way
   better than before. Sometimes it feels like it overshoots a little."* The
   cause was the second lag: a panel trailed the anchor at its own 2.5 s, so
   when the anchor turned round the panel carried on until the anchor passed
   back through it. **Panels now track the anchor at 0.1 s during a
   head-triggered return** (`Scene.tightPanelTracking`, `tightoff` reverts), so
   the anchor alone carries the drift pace. Worn blind (`trackab`, 3 phases
   each): *"B feels a lot better than A"*, B being tight tracking. Run-on in
   the unit test went from ~1° to under 0.3°.
4. **The deadzone is a gate, not a stopping point.** Once pulled outside it, a
   panel returns the whole way to its slot.
5. **The return is anchored on the panel the wearer last looked at**, so what
   they were reading comes back in front of them rather than the gap between
   panels.
6. **Pinned panels are obstacles to where a follower comes to rest, not to its
   journey.** It crosses over, briskly, and parks clear.
7. **A layout change is a command, not a head movement**: pinning or re-spacing
   moves panels regardless of the band, but as a drift and never as a jump.
8. **A panel placed by hand keeps its place.** Grabbing writes the placement
   into the panel's slot, and a layout triggered by something unrelated leaves
   it alone — the same claim pinning makes.
9. **The arrangement hangs from one remembered heading**, and every panel's
   position is derived from it. Nothing about where panels have been *pushed to*
   may feed back into it.
10. **Where a panel would like to be is per-obstacle; where it can be is a
    question about all of them at once.**

## Grab and move

Fourth session, and worn from the first minute. Everything in the version of
this section written before the glasses went on was either wrong or incomplete;
what follows is what a head found.

A grab is three moments, and the interesting one is the release:

| Moment | What happens |
|---|---|
| `beginGrab` | Records the panel's offset from the pointer, so it is dragged by the point it was taken hold of |
| `updateGrab` | Once per frame, before following: the held panel's pose is the pointer plus that offset |
| `endGrab` | Writes the placement into the frame the panel is stored in |

**A grab freezes the arrangement.** The first thing the wearer hit: carrying a
panel to the right of its neighbour was impossible, because the neighbour was
both chasing their heading and re-parking clear of the panel being carried, at
crossing speed. *"I can't put it to the right of the second panel, it jumps way
ahead."* Placement is done against the rest of the arrangement, so the rest of
the arrangement has to hold still while it happens. The layout resolves when the
grab ends.

**The release measures against the arrangement's anchor, not the wearer's
heading.** A pinned panel already lives in world angles and needs nothing
recorded. A follower's pose is derived from its slot every frame, so the
placement has to become a slot — and the wearer places a panel relative to the
*panels they can see*, which sit where their slots put them. Those two agree only
when the arrangement is settled, and during a grab it is deliberately not: the
freeze holds the panels still while the head turns. Measured from the head, a
panel put down to the right of its neighbour came back on its left, overlapping
it, at the first head movement large enough to make the pair follow. Measured
from the anchor, letting go never moves the panel at all.

**A held panel is lifted 15 cm toward the wearer.** Two panels at the same radius
that cross intersect as planes: each occludes half the other along the seam,
which reads as clipping rather than as one passing in front. Depth is not
available for *resolving* an overlap — one virtual image distance — but this is a
lift for as long as the panel is in the wearer's hand, and it has to clear the
~8 cm of depth a panel's own width spans at 4 m. The vergence change is under
0.04°, and being drawn whole is what makes it look picked up.

**Elevation is clamped to 25°**, and the wearer's own words for that limit were
"I can look higher but it's uncomfortable" — the clamp stopped the panel exactly
where they stopped wanting to follow it. Pitch needs no slot: gravity is an
absolute reference, unlike yaw.

**A hand-placed slot is a claim, like pinning.** Opening or closing a panel
leaves it alone; asking for a new slot spacing overrules it. It is also not
re-centred on: the return anchors on the panel the wearer last looked at, which
exists to repair a layout they did not choose — and doing it to a panel they
placed 40° to the right slid it back in front of them, dragging the arrangement
across its neighbour, every time they glanced at it and turned away.

## Avoidance, rewritten by a head

The three-panel case with one panel pinned broke every part of the collision
model in a different way. The rules that came out of it:

**The arrangement's anchor is remembered, not inferred.** It used to be averaged
from where the followers were sitting relative to their slots. That makes the
band consult a quantity avoidance changes: pushing a panel clear of a pinned one
moves the average, which re-triggers the return, which drives the panel back onto
the obstacle. Worn, that was two followers charging at a pinned panel and
snapping back for as long as the wearer looked at it. The arrangement now hangs
from one remembered heading; panels track their slots relative to it.

**Whether a panel can be somewhere is a question about every obstacle at once.**
Each obstacle used to adjust the destination in turn, so the last one to speak
won: the pinned panel pushed a follower to its right, the follower beyond that
pushed it back, and it came to rest exactly on the pinned panel — the side it had
been sent to had no room for it at all. Per-obstacle rules decide where a panel
would *like* to be; a feasibility pass over all of them decides where it can
actually go, choosing the nearest point to its slot that everything can live
with.

**Neighbours must be resolved against where they park, not where their slot
wants them.** A panel pushed clear of a pinned obstacle rests a long way from the
slot it was aiming at. With panels resolved in slot order and each asking the
others for their *slot* target, nobody ever saw the collision, and a pair sat 8°
inside each other indefinitely. Resolved targets are kept across frames.

**A side commitment is forgotten once the panel has no business there.** A side
is only re-decided after the panel *arrives* on the side it claimed — so a
follower committed to the far side of a pinned panel during one sweep was still
being ordered there afterwards, when its own slot had carried it 65° away on the
other side. Every frame it was told to cross the whole field of view, and it
shoved its neighbour aside on the way. If the slot destination is already clear
of the obstacle, there is no side question and any remembered answer is dropped.

**Crossing speed is for passing through an obstacle, and nothing else.** Tied to
the committed side instead, a follower 60° clear of a pinned panel dashed across
the view at 150°/s for no visible reason.

**Elevation gates avoidance.** A panel parked 25° up is not in anyone's way, and
a model that only knows headings insists that it is — it went on shoving its
neighbours aside, and into each other, from somewhere nobody was looking. Two
panels overlap only if they overlap in both yaw and pitch.

**Followers give way to each other**, which they did not before (`AVOID_ALL` is
now the default). The clearance between two followers has to be *narrower* than
the slot spacing, or a correctly laid out pair counts as colliding for ever and
the avoidance fights the layout that placed them: 24.7° of separation plus the
6° that ends a crossing crisply is 30.7°, wider than the 30° spacing. Followers
get 2°. And the panel the wearer placed by hand is not the one that gives way.

**Two followers straddle a pinned panel rather than crowding it.** Their side
used to come from where they happened to be, so they always agreed and ended up
glued together on one side. It comes from their slots instead — one belongs left,
one right — and gaze still decides *changes* of side at the obstacle's own edges.
The wearer's words: *"they should be allowed to be separated, but not too
aggressively — not when looking right at the pinned panel, but after crossing
it."*

**An overlap is its own reason to move.** The band must not gate it, or two
panels left lying on each other stay that way until the wearer happens to turn
far enough to trigger following. It resolves in place: every panel's destination
is where it already is, so only the pair that actually overlap move.

## How fast the arrangement moves

Three paces, and they are not interchangeable:

| Movement | Constant | Floor | Why |
|---|---|---|---|
| Drift correction | 2.5 s | 6°/s | The wearer did not ask; they should not notice |
| Catch-up | 0.2 s | 150°/s | They turned to look at something and are waiting |
| Crossing | 0.25 s | 150°/s | Passing through a state nobody wants |

A movement is a catch-up when the wearer has looked more than a panel's width
past the band — their own unit, from *"looking past the pinned panel about a
panel's worth of distance"*. `catchup1` … `catchup4` set the pace worn; 0.2 s and
150°/s is the wearer's pick.

Note that the arrangement and the panels within it each smooth their own motion,
so a catch-up is two lags in series and reads slower than either constant
suggests.

## Consequences for the window manager

- **Slot frames and world frames cannot be reconciled at layout time.** A
  follower's slot is relative to the wearer; a pinned panel lives in world
  angles, and its position relative to the wearer changes every time they turn.
  That is why avoidance has to run per frame, and why "the follower gets centre"
  and "centre is free" are independent facts.
- **Depth is not available for resolving overlaps.** The optics form the virtual
  image at ~4 m, so dodging in depth trades an overlap for a
  vergence/accommodation conflict. Elevation is no better: 19° of vertical field,
  most of it used by the panel. Resolution is in yaw, which is also why "the
  follower wins" is not a distinct policy — in yaw, winning *is* overlapping.
- **Slot spacing wants to be a user setting.** 30° (7.3° of gap between
  22.7°-wide panels) read as slightly too wide worn. `slots30/40/50` exist to
  find the preference; the eventual settings app should own it.
- **Followers may want to be distributed around a pinned panel** rather than
  crowded on one side. With a follower on each side of a pinned panel, something
  is always already where the wearer is looking, and the arrangement felt better
  than the two-panel case did.

## Still unverified

- **A snap turn** — head still, whip, hold — is the one regime never captured.
  It is the case where 50 ms of rate smoothing could cost visible lag, and where
  a longer constant would be worse. 80 ms measured better than 50 in both
  captured regimes; 50 is the default until a snap is measured.
- **The catch-up starting late is fixed.** Not run blind, deliberately: the
  wearer noticed the change the moment it was on and called it much better,
  and an A/B would only confirm what was obvious at first sight. The pace was
  decided once, when the band was crossed — the moment the wearer is barely
  past it — so a continuing turn was answered at the drift pace: 7.3 s to catch
  a 90° turn made over one second, in the unit test. Now a head-triggered
  return is raised to catch-up as soon as the wearer is a panel's width past
  the band *measured from where the arrangement is now*, and never lowered
  again (`Scene.catchUpMidMove`). Continuous blending, the version that was
  reverted, is not what this is.

  Two focus fixes go with it, because focus is what broke last time:
  - **`focusHeld`**: during a catch-up, a commanded move or a crossing, a panel
    sweeping under a gaze that has not moved does not take focus.
  - **Passing over is not looking.** A panel only becomes the one the next
    return centres on if the head was turning slower than 30°/s when the gaze
    landed on it. Worn, before this: *"looking past the opposite side focuses
    the other panel on the way most of the time"* — the gaze crossed the
    neighbour on the way out, and that counted.
  - **A drift waits for the head to settle** (0.3 s under 30°/s). The previous
    fix had a side effect, worn: *"looking past the second panel just a little
    triggers a slow re-center ... on the first panel ... it starts then
    stops."* The drift began mid-glance, latched on the panel the gaze had
    come from, and settling back cancelled it. A catch-up still starts at
    once — a panel's width past the band is a turn, not an overshoot.
  - **The wait alone was not enough**, worn: *"I don't think a wait is the
    right fix, maybe combining it with a minimum trigger distance? I'm still
    having the issue sometimes."* Holding a small overshoot outlasted the wait,
    and the drift then centred the panel the glance *came from*, because a fast
    glance never counts as looking. Two changes: a drift needs **6° past the
    band** (`DRIFT_MIN_EXCESS`) on top of its own margin, and **a drift centres
    the follower nearest the gaze**, while a catch-up still brings back the
    panel last looked at. Different movements, different intent: a drift is a
    wearer at rest just past the arrangement, heading for its edge panel; a
    catch-up is a wearer who turned away from what they were reading.
    Worn after both: *"it feels alright now."*

  Worn once, unblinded (the A/B was not actually running): *"almost instantly,
  about half a second to catch up with where I'm currently looking. Feels about
  right for me"* — **but the wearer wants the pace configurable** in the final
  version, since it is subjective. `catchUpSeconds` / `catchUpDegreesPerSecond`
  are already the knobs; the settings app should own them alongside slot
  spacing.
- **The phone pointer with grab** — the two-finger grab gesture on the pointer
  pad is written and was never reached; every grab this session was head gaze.
- **Cursor depth across panels** at a deliberate overlap.
