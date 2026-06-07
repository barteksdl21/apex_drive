# apex_drive

Core route generation prototype for fun-car drivers (Miatas, hot hatches, Porsches, etc.).

Current core experience implemented:
- Input: start position, preferred drive length (km), and route mode (`CLOSED_LOOP` or `ONE_WAY`)
- Output: top 2-3 route suggestions ranked by road quality (curviness, elevation, scenery) and length fit
- Route quality guardrails: avoids immediate U-turns and keeps routes near preferred length

Run:
- `./gradlew run`
- `./gradlew test --tests com.apexdrive.AppTest`
