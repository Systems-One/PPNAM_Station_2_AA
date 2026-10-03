import ui, time
# login if on login screen
labs = ui.labels()
if any("Username" in x for x in labs):
    ui.type_into("Username","operator1"); ui.type_into("Password","pass"); ui.tap("Log In")
    ui.wait_for(lambda ls: any("Look Up" in x or "Active Jobs" in x for x in ls), timeout=25)
time.sleep(1)
ui.tap("Mixing")
ui.wait_for(lambda ls: any("Main Mixing Room" in x for x in ls), timeout=20); time.sleep(1)
ui.tap("Main Mixing Room")
ui.wait_for(lambda ls: any("MXR-01" in x or "Ready mixes" in x for x in ls), timeout=20); time.sleep(1.5)
print("BOARD labels:", [x for x in ui.labels() if any(k in x for k in ("MIX_000001","Ready mixes","Collections ready","MXR-01","Extruder"))][:8])
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E-board-newbuild.png")
