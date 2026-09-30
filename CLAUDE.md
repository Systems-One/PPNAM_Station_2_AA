# Repo Rules

## External directory: C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2

This is the sibling WPF/Core/CLI repo for PPNAM Station 2 (not this Android app). When reading or referencing files there:

- The **only** file in `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2` that may be edited or written to is `RFID_MQTT_CONTRACT.md`.
- Every other file and folder under `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2` (including `Android_App`, `DOCS`, `Database`, `PPNAM.Station2.CLI`, `PPNAM.Station2.Core`, `PPNAM.Station2.Tests`, `PPNAM.Station2.WPF`, `SAP_Sample_Data`, `references`, and all other `.md`/`.slnx` files) is **read-only** — treat it as reference material only, never edit or write to it.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
