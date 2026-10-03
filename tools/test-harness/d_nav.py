import ui, time
ui.tap("Mixing")
ui.wait_for(lambda ls: any("Main Mixing Room" in x for x in ls), timeout=15); time.sleep(1)
ui.tap("Main Mixing Room")
ui.wait_for(lambda ls: any("MXR-01" in x or "Ready mixes" in x for x in ls), timeout=15); time.sleep(1.5)
print("READY MIX PRESENT:", any("MIX_000001" in x for x in ui.labels()))
print("BOARD:", [x for x in ui.labels() if any(k in x for k in ("MIX_000001","Ready mixes","From Main"))])
