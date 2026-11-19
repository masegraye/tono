# backlog-043: A relay test sometimes fails under load

| | |
|---|---|
| Status | captured |
| Area | Tests |
| Size | small |
| Source | seen while merging backlog-042, 2026-11-19 |

## What

`server/test/TonoRelayGonePageTest.kt`, "and so is one that ends its connection without saying goodbye" (line ~206), failed once in a full `task test` run with `expected: <dropped> but was: <null>`: the stand-in server hadn't seen the upstream connection end yet when the test looked. Run alone five times right after, it passed every time. The test runs a real relay with 300 ms / 700 ms timings, so a busy machine (all modules testing in parallel) can push it past its wait.

## Why

A test that fails now and then teaches people to ignore failures.

## Builds on

backlog-037 (commit `0974bf2`), which added the test.

## Promote when

Next time anything touches the relay, or sooner if it fails again.

## Not known yet

Whether to wait for the outcome (poll until the stand-in records it, with a generous ceiling) rather than sleep a fixed time, or to give the test its own longer timings.

## Seen again

2026-11-19, while merging backlog-053: "a page that is there and has nothing to say stays connected" in the same file failed once in a full `task test` run (`expected: <closed 1000> but was: <dropped>`), then passed five times on its own. Two of the file's tests are now known to fail under load, so this is worth doing soon.
