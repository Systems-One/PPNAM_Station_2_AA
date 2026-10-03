import ui, time
ui.type_into("Username","operator1"); ui.type_into("Password","pass"); ui.tap("Log In")
ok = ui.wait_for(lambda ls: any("Look Up" in x or "Active Jobs" in x for x in ls), timeout=20)
time.sleep(1); print("logged in:", bool(ok)); print("LABELS:", ui.labels()[:8])
