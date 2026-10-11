# OpenCode Pocket acceptance checklist

Do not mark these as passed based on the UI rendering alone. Run them on a real ARM64 Android device.

## A. Build / install

- [ ] GitHub Actions `assembleDebug` job finishes successfully.
- [ ] Install `app-debug.apk` on Android 9+ ARM64.
- [ ] App launches, opens Settings, saves working directory, and returns without crash.
- [ ] Android 13+ notification permission path works whether permission is granted or denied.

## B. Termux bridge and API

- [ ] Termux has `allow-external-apps = true` and was restarted after the setting changed.
- [ ] Android Settings grants OpenCode Pocket the Termux Run Commands permission.
- [ ] The app detects an already-running `http://127.0.0.1:4096/global/health` server.
- [ ] With the server stopped, tapping Connect/Start launches `opencode serve --hostname 127.0.0.1 --port 4096` in the configured directory.
- [ ] The app shows a useful error if Termux is missing, RUN_COMMAND is denied, port 4096 is busy, or the API cannot start.
- [ ] App-launched server requires Basic Auth; API requests without the random password receive 401.
- [ ] A manually started no-password server triggers a visible security warning.
- [ ] API `GET /path` matches the expected working directory.
- [ ] Sessions and provider/model list load from the real server.

## C. Real agent conversation

- [ ] Start a new session and send a simple prompt.
- [ ] Confirm the request appears in `GET /session/:id/message` and the answer comes from the configured model (not the UI).
- [ ] Ask the agent to list project files; verify the real tool call appears.
- [ ] Ask the agent to make a small change to a disposable test project and verify the file really changes.
- [ ] Select a non-default provider/model and confirm the request uses that model; then return to OpenCode default.
- [ ] Abort a running task and confirm the server status stops.
- [ ] Switch between existing sessions; verify the correct history is shown.

## D. Background / power

- [ ] Grant notifications and run a task long enough to see an ongoing notification.
- [ ] Lock the screen for at least 10 minutes while an agent task is still working; check the actual file/process result afterward.
- [ ] Switch to another app and return; verify the conversation refreshes from the server.
- [ ] Verify Termux's wake lock is active and release it after testing with `termux-wake-unlock`.
- [ ] Test with battery optimization enabled and disabled for both OpenCode Pocket and Termux; record device-specific results.
- [ ] Force-stop behavior is documented as interruption, not reported as continued execution.

## E. Current build status

- [ ] **Not yet run in this authoring environment.** No Android SDK/Gradle installation or attached Android device was available to the implementation session.
