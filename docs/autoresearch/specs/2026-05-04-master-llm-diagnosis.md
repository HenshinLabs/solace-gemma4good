# Master LLM App — Autoresearch Diagnosis Spec

## Problem Statement

Target repo: `Master_LLM_app` at `/home/shuvam/codes/Master_LLM_app`

Goal: Onboard this repo into autoresearch experiment loop for automated bug fixing and performance optimization.

## Compatibility Label: `v2-required`

### Evidence

1. **No ML training code** — Zero Python files, training scripts, or Jupyter notebooks in main repo. All `.py` files reside inside `llama.cpp/` git submodule only.

2. **Non-Python entry point** — Primary build/test entry is `./gradlew assembleDebug` (Gradle/Android SDK). No `train.py`, `main.py`, or `run.py` exists.

3. **No single scalar metric** — App produces no training metric. Performance metrics (tokens/sec, CPU usage) are point-in-time inference stats, logged via Timber/Logcat, not stdout storable.

4. **Unbounded run time** — Android build + emulator boot + app launch takes minutes. Cannot bound under a configurable timeout for short experiment loops.

5. **No clear adapter path** — Wrapping an Android build/test cycle into a V1 training loop would require rewriting the repo's execution model, which is outside the V1 adapter boundary.

### Blocker Reason

Repo is an Android application for on-device LLM inference, not an ML research training repo. The autoresearch V1 profile requires a single-process training entry point, extractable scalar metric, and sub-10-minute run time — none of which this repo satisfies. Onboarding would require fundamental architectural changes that violate the V1 adapter contract.

## What Would Need To Change for V1

- A Python-trainable model with a `train.py` entry point
- Single scalar metric logged to stdout or file per training run
- Run time under 600 seconds
- No Android SDK / NDK build dependency

## Next Steps Outside Autoresearch

The original goal (fixing 32 identified bugs) is better served by:
1. Direct code edits to the identified bug locations (see earlier bug report)
2. Gradle-based test execution (`./gradlew test`, `./gradlew connectedCheck`)
3. Manual ADB install + logcat verification on emulator (already set up)

Autoresearch can be revisited if a Python training component is added to the repo.
