# TV Guardian

A parental control system for Google TV (Hisense) and, later, LG TVs. A parent's Android phone can see what's playing on the TV, pause or stop it, lock the TV, and set screen-time limits, schedules and app rules from anywhere.

- **Plan:** [docs/PLAN.md](docs/PLAN.md)
- **Setup and testing:** [docs/SETUP.md](docs/SETUP.md)

## Status

**Phase 0, feasibility test.** `app-tv` is a debug build of the TV agent. It checks that the TV lets us read what's playing, pause it, lock the screen and go home.

## Layout

| Path | What |
|---|---|
| `app-tv/` | The app that runs on the TV (Kotlin, Compose for TV) |
| `tools/setup-tv.ps1` | One-time install and permission setup over ADB |
| `tools/pull-log.ps1` | Copies the test log from the TV |
| `docs/` | Plan and setup guide |

`core/` (shared rules engine), `app-parent/` (phone app) and `firebase/` arrive in Phase 1.
