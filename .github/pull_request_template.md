<!--
Thanks for the PR! Please skim CONTRIBUTING.md if you haven't.
-->

## What this changes

<!-- One or two sentences. -->

## Why

<!-- What real failure mode does this prevent or fix? -->

## How

<!-- Brief technical sketch. -->

## Verification

- [ ] `./mvnw verify` passes locally
- [ ] `./mvnw spotless:apply` ran (no formatting drift)
- [ ] New behaviour has at least one test that would catch a regression
- [ ] If hot-path: ran `./mvnw -Pbench package exec:java` and the numbers are sane
- [ ] Updated `CHANGELOG.md` under `[Unreleased]`

## Anything reviewers should pay attention to?
