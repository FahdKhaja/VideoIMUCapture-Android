# tools

## drive_cell.ps1 — fire a TestPlan cell from the desktop

Runs one cell of the test matrix on the connected phone and waits for its receipt:

    .\drive_cell.ps1 -Cell W1                    # from "To shoot"
    .\drive_cell.ps1 -Cell M5 -List "Done*"      # reshoot a cell already ticked off

Every button is located from a live `uiautomator dump` — START moves with the length of the
cell's instruction text, Done-list rows carry a `✓  ` prefix — so nothing is hard-coded except
the overflow menu at the top right of a 1440x3120 screen. It prints the receipt's key lines and
the app's log for the run.

Needs: the phone unlocked and on the app's main screen (or anywhere the app can be launched
to), and `adb` on the path. Use PowerShell, not Git Bash, for anything with `/sdcard/` paths.
Only cells that need no kit are drivable this way (M*, L1, W*, N*); G1/G2 need the target and
tape, H/I/O need a walk.

On 2026-09-20 this ran M5, L1 (x4), W1, W2, N1, N2 and found, in the receipts alone: a
still request whose frame was never the one kept, a row counter that never reset between
video clips, and a lens check that disagreed with clips that were doing exactly what they
were asked.
